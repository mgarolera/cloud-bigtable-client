/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.grpc.async;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import com.google.api.client.repackaged.com.google.common.base.Preconditions;
import com.google.bigtable.v1.ReadRowsRequest;
import com.google.bigtable.v1.Row;
import com.google.bigtable.v1.RowFilter;
import com.google.bigtable.v1.RowSet;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.grpc.BigtableDataClient;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;

/**
 * This class combines a collection of {@link ReadRowsRequest}s with a single row key into a single
 * {@link ReadRowsRequest} with a {@link RowSet} which will result in fewer round trips. This class
 * is not thread safe, and requires calling classes to make it thread safe.
 */
public class BulkRead {

  protected static final Logger LOG = new Logger(BulkRead.class);

  private final AsyncExecutor asyncExecutor;
  private final String tableName;

  /**
   * ReadRowRequests have to be batched based on the {@link RowFilter} since {@link ReadRowsRequest}
   * only support a single RowFilter.
   */
  private RowFilter currentFilter;

  /**
   * Maps row keys to a collection of {@link SettableFuture}s that will be populated once the batch
   * operation is complete. The value of the {@link Multimap} is a {@link SettableFuture} of
   * a {@link List} of {@link Row}s.  The {@link Multimap} is used because a user could request
   * the same key multiple times in the same batch. The {@link List} of {@link Row}s mimics the
   * interface of {@link BigtableDataClient#readRowsAsync(ReadRowsRequest)}.
   */
  private Multimap<ByteString, SettableFuture<List<Row>>> futures;

  public BulkRead(AsyncExecutor asyncExecutor, String tableName) {
    this.asyncExecutor = asyncExecutor;
    this.tableName = tableName;
  }

  /**
   * Adds the key in the request to a list of to look up in a batch read.
   *
   * @param request a {@link ReadRowsRequest} with a single row key.
   *
   * @return a {@link ListenableFuture} that will be populated with the {@link Row} that 
   *    corresponds to the request
   * @throws InterruptedException
   */
  public ListenableFuture<List<Row>> add(ReadRowsRequest request) throws InterruptedException {
    Preconditions.checkNotNull(request);
    ByteString rowKey = request.getRowKey();
    Preconditions.checkArgument(!rowKey.equals(ByteString.EMPTY));

    RowFilter filter = request.getFilter();
    if (currentFilter == null) {
      currentFilter = filter;
    } else if (!filter.equals(currentFilter)) {
      // TODO: this should probably also happen if there is some maximum number of 
      flush();
      currentFilter = filter;
    }
    if (futures == null) {
      futures = HashMultimap.create();
    }
    SettableFuture<List<Row>> future = SettableFuture.create();
    futures.put(rowKey, future);
    return future;
  }

  /**
   * Sends all remaining requests to the server. This method does not wait for the method to
   * complete.
   * @throws InterruptedException
   */
  public void flush() throws InterruptedException {
    if (futures != null && !futures.isEmpty()) {
      // TODO(sduskis): remove this once bulk read testing is complete.
//      LOG.info("BulkRead reading %d rows.", futures.keys().size());
      ReadRowsRequest request =
          ReadRowsRequest.newBuilder()
              .setTableName(tableName)
              .setFilter(currentFilter)
              // This is a performance improvement for this specific case where ordering doesn't
              // matter and the entire batch is retried.
              .setAllowRowInterleaving(true)
              .setRowSet(RowSet.newBuilder().addAllRowKeys(futures.keys()).build())
              .build();
      Futures.addCallback(asyncExecutor.readRowsAsync(request), createFuture(futures));
    }
    futures = null;
    currentFilter = null;
  }

  /**
   * Creates a {@link FutureCallback} that sets all of the {@link SettableFuture}s that were created
   * in {@link BulkRead#add(ReadRowsRequest)}.
   */
  protected FutureCallback<List<Row>> createFuture(
      final Multimap<ByteString, SettableFuture<List<Row>>> futures) {
    return new FutureCallback<List<Row>>() {

      @Override
      public void onSuccess(List<Row> result) {
        for (Row row : result) {
          Collection<SettableFuture<List<Row>>> rowFutures = futures.get(row.getKey());
          // TODO: What about missking keys?
          for (SettableFuture<List<Row>> rowFuture : rowFutures) {
            rowFuture.set(ImmutableList.of(row));
          }
          futures.removeAll(row.getKey());
        }
        Collection<Entry<ByteString, SettableFuture<List<Row>>>> entries =
            futures.entries();
        for (Entry<ByteString, SettableFuture<List<Row>>> entry : entries) {
          entry.getValue().set(ImmutableList.<Row> of());
        }
      }

      @Override
      public void onFailure(Throwable t) {
        for (Entry<ByteString, SettableFuture<List<Row>>> entry : futures.entries()) {
          entry.getValue().setException(t);
        }
      }
    };
  }
}
