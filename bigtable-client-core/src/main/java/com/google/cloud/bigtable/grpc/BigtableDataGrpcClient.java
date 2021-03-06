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
package com.google.cloud.bigtable.grpc;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.bigtable.v1.BigtableServiceGrpc;
import com.google.bigtable.v1.CheckAndMutateRowRequest;
import com.google.bigtable.v1.CheckAndMutateRowResponse;
import com.google.bigtable.v1.MutateRowRequest;
import com.google.bigtable.v1.MutateRowsRequest;
import com.google.bigtable.v1.MutateRowsRequest.Entry;
import com.google.bigtable.v1.MutateRowsResponse;
import com.google.bigtable.v1.Mutation;
import com.google.bigtable.v1.ReadModifyWriteRowRequest;
import com.google.bigtable.v1.ReadRowsRequest;
import com.google.bigtable.v1.ReadRowsResponse;
import com.google.bigtable.v1.Row;
import com.google.bigtable.v1.SampleRowKeysRequest;
import com.google.bigtable.v1.SampleRowKeysResponse;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.cloud.bigtable.grpc.async.BigtableAsyncUtilities;
import com.google.cloud.bigtable.grpc.async.BigtableAsyncRpc;
import com.google.cloud.bigtable.grpc.io.CancellationToken;
import com.google.cloud.bigtable.grpc.io.ChannelPool;
import com.google.cloud.bigtable.grpc.io.ClientCallService;
import com.google.cloud.bigtable.grpc.scanner.BigtableResultScannerFactory;
import com.google.cloud.bigtable.grpc.scanner.ResponseQueueReader;
import com.google.cloud.bigtable.grpc.scanner.ResultScanner;
import com.google.cloud.bigtable.grpc.scanner.ResumingStreamingResultScanner;
import com.google.cloud.bigtable.grpc.scanner.StreamingBigtableResultScanner;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.ServiceException;

/**
 * A gRPC client to access the v1 Bigtable service.
 */
public class BigtableDataGrpcClient implements BigtableDataClient {

  private static final Logger LOG = new Logger(BigtableDataGrpcClient.class);
  private static AtomicInteger LOG_COUNT = new AtomicInteger();

  @VisibleForTesting
  public static final Predicate<MutateRowRequest> IS_RETRYABLE_MUTATION =
      new Predicate<MutateRowRequest>() {
        @Override
        public boolean apply(MutateRowRequest mutateRowRequest) {
          return mutateRowRequest != null
              && allCellsHaveTimestamps(mutateRowRequest.getMutationsList());
        }
      };

  @VisibleForTesting
  public static final Predicate<MutateRowsRequest> ARE_RETRYABLE_MUTATIONS =
      new Predicate<MutateRowsRequest>() {
        @Override
        public boolean apply(MutateRowsRequest mutateRowsRequest) {
          if (mutateRowsRequest == null) {
            return false;
          }
          for (Entry entry : mutateRowsRequest.getEntriesList()) {
            if (!allCellsHaveTimestamps(entry.getMutationsList())) {
              return false;
            }
          }
          return true;
        }
      };

  @VisibleForTesting
  public static final Predicate<CheckAndMutateRowRequest> IS_RETRYABLE_CHECK_AND_MUTATE =
      new Predicate<CheckAndMutateRowRequest>() {
        @Override
        public boolean apply(CheckAndMutateRowRequest checkAndMutateRowRequest) {
          return checkAndMutateRowRequest != null
              && allCellsHaveTimestamps(checkAndMutateRowRequest.getTrueMutationsList())
              && allCellsHaveTimestamps(checkAndMutateRowRequest.getFalseMutationsList());
        }
      };

  private static final boolean allCellsHaveTimestamps(Iterable<Mutation> mutations) {
    for (Mutation mut : mutations) {
      if (mut.getSetCell().getTimestampMicros() == -1) {
        return false;
      }
    }
    return true;
  }

  private final ChannelPool channelPool;

  private final ExecutorService executorService;
  private final RetryOptions retryOptions;
  private final BigtableOptions bigtableOptions;
  private final BigtableResultScannerFactory streamingScannerFactory =
      new BigtableResultScannerFactory() {
        @Override
        public ResultScanner<Row> createScanner(ReadRowsRequest request) {
          return streamRows(request);
        }
      };
  private BigtableAsyncRpc<SampleRowKeysRequest, List<SampleRowKeysResponse>> sampleRowKeysAsync;
  private BigtableAsyncRpc<ReadRowsRequest, List<Row>> readRowsAsync;

  private ClientCallService clientCallService;

  public BigtableDataGrpcClient(
      ChannelPool channelPool,
      ExecutorService executorService,
      BigtableOptions bigtableOptions) {
    this(channelPool, executorService, bigtableOptions, ClientCallService.DEFAULT);
  }

  @VisibleForTesting
  BigtableDataGrpcClient(
      ChannelPool channelPool,
      ExecutorService executorService,
      BigtableOptions bigtableOptions,
      ClientCallService clientCallService) {
    this.channelPool = channelPool;
    this.executorService = executorService;
    this.bigtableOptions = bigtableOptions;
    this.retryOptions = bigtableOptions.getRetryOptions();
    this.clientCallService = clientCallService;

    this.sampleRowKeysAsync =
        BigtableAsyncUtilities.createSampleRowKeyAsyncReader(this.channelPool, clientCallService);
    this.readRowsAsync =
        BigtableAsyncUtilities.createRowKeyAysncReader(this.channelPool, clientCallService);
  }

  @Override
  public Empty mutateRow(MutateRowRequest request) throws ServiceException {
    return performBlockingRpc(
        request, BigtableServiceGrpc.METHOD_MUTATE_ROW, IS_RETRYABLE_MUTATION);
  }

  @Override
  public ListenableFuture<Empty> mutateRowAsync(MutateRowRequest request) {
    return performAsyncRpc(request, BigtableServiceGrpc.METHOD_MUTATE_ROW, IS_RETRYABLE_MUTATION);
  }

  @Override
  public MutateRowsResponse mutateRows(MutateRowsRequest request) throws ServiceException {
    return performBlockingRpc(
      request, BigtableServiceGrpc.METHOD_MUTATE_ROWS, ARE_RETRYABLE_MUTATIONS);
  }

  @Override
  public ListenableFuture<MutateRowsResponse> mutateRowsAsync(MutateRowsRequest request) {
    return performAsyncRpc(request, BigtableServiceGrpc.METHOD_MUTATE_ROWS, ARE_RETRYABLE_MUTATIONS);
  }

  @Override
  public CheckAndMutateRowResponse checkAndMutateRow(CheckAndMutateRowRequest request)
      throws ServiceException {
    return performBlockingRpc(
        request, BigtableServiceGrpc.METHOD_CHECK_AND_MUTATE_ROW, IS_RETRYABLE_CHECK_AND_MUTATE);
  }

  @Override
  public ListenableFuture<CheckAndMutateRowResponse> checkAndMutateRowAsync(
      CheckAndMutateRowRequest request) {
    return performAsyncRpc(
        request, BigtableServiceGrpc.METHOD_CHECK_AND_MUTATE_ROW, IS_RETRYABLE_CHECK_AND_MUTATE);
  }

  private <ReqT, RespT> RespT performBlockingRpc(
      ReqT request,
      MethodDescriptor<ReqT, RespT> method,
      Predicate<ReqT> retryablePredicate) {
    CancellationToken token = new CancellationToken();
    BigtableAsyncRpc<ReqT, RespT> rpc =
        BigtableAsyncUtilities.createAsyncUnaryRpc(channelPool, clientCallService, method, token);

    try {
      ListenableFuture<RespT> rpcFuture = performRetryingAsyncRpc(request, rpc, retryablePredicate);
      return BigtableAsyncUtilities.getUnchecked(rpcFuture);
    } catch (Throwable t) {
      token.cancel();
      throw Throwables.propagate(t);
    }
  }

  private <ReqT, RespT> ListenableFuture<RespT> performAsyncRpc(
      ReqT request,
      MethodDescriptor<ReqT, RespT> method,
      Predicate<ReqT> predicate) {
    BigtableAsyncRpc<ReqT, RespT> asyncRpc =
        BigtableAsyncUtilities.createAsyncUnaryRpc(channelPool, clientCallService, method);

    return performRetryingAsyncRpc(request, asyncRpc, predicate);
  }
  
  private <ReqT, RespT> ListenableFuture<RespT> performRetryingAsyncRpc(ReqT request,
      BigtableAsyncRpc<ReqT, RespT> rpc, Predicate<ReqT> isRetryable) {
    if (retryOptions.enableRetries() && isRetryable.apply(request)) {
      return BigtableAsyncUtilities.performRetryingAsyncRpc(retryOptions, request, rpc,
        executorService);
    } else {
      if (retryOptions.enableRetries()) {
        // Do not retry the call despite retries being enabled. The call is not idempontent and
        // retrying it could cause unexpected behavior.
        // Only log for powers of two to avoid spam.
        int count = LOG_COUNT.incrementAndGet();
        if ((count & (count - 1)) == 0) {
          LOG.info("Retries configured for non-retryiable request. Not retrying. "
              + "In future releases this case will fail.");
        }
      }
      return rpc.call(request);
    }
  }

  @Override
  public Row readModifyWriteRow(ReadModifyWriteRowRequest request) {
    return clientCallService.blockingUnaryCall(
      channelPool.newCall(BigtableServiceGrpc.METHOD_READ_MODIFY_WRITE_ROW, CallOptions.DEFAULT),
      request);
  }

  @Override
  public ListenableFuture<Row> readModifyWriteRowAsync(ReadModifyWriteRowRequest request) {
    return clientCallService.listenableAsyncCall(
      channelPool.newCall(BigtableServiceGrpc.METHOD_READ_MODIFY_WRITE_ROW, CallOptions.DEFAULT),
      request);
  }

  @Override
  public ImmutableList<SampleRowKeysResponse> sampleRowKeys(SampleRowKeysRequest request) {
    return ImmutableList
        .copyOf(clientCallService.blockingServerStreamingCall(
          channelPool.newCall(BigtableServiceGrpc.METHOD_SAMPLE_ROW_KEYS, CallOptions.DEFAULT),
          request));
  }

  @Override
  public ListenableFuture<List<SampleRowKeysResponse>> sampleRowKeysAsync(
      SampleRowKeysRequest request) {
    return BigtableAsyncUtilities.performRetryingAsyncRpc(retryOptions, request, sampleRowKeysAsync,
      executorService);
  }

  @Override
  public ListenableFuture<List<Row>> readRowsAsync(final ReadRowsRequest request) {
    return BigtableAsyncUtilities
        .performRetryingAsyncRpc(retryOptions, request, readRowsAsync, executorService);
  }

  @Override
  public ResultScanner<Row> readRows(ReadRowsRequest request) {
    // Delegate all resumable operations to the scanner. It will request a non-resumable
    // scanner during operation.
    if (retryOptions.enableRetries()) {
      return new ResumingStreamingResultScanner(retryOptions, request, streamingScannerFactory);
    } else {
      return streamRows(request);
    }
  }

  private ResultScanner<Row> streamRows(ReadRowsRequest request) {
    boolean isGet = request.getTargetCase() == ReadRowsRequest.TargetCase.ROW_KEY;

    int streamingBufferSize;
    int batchRequestSize;

    if (isGet) {
      // Batch request size is more performant with a value of 1 for single row gets, while a higher
      // number is more performant for scanning
      batchRequestSize = 1;
      streamingBufferSize = 10;
    } else {
      batchRequestSize = retryOptions.getStreamingBatchSize();
      streamingBufferSize = retryOptions.getStreamingBufferSize();
    }

    ClientCall<ReadRowsRequest, ReadRowsResponse> readRowsCall =
        channelPool.newCall(BigtableServiceGrpc.METHOD_READ_ROWS, CallOptions.DEFAULT);

    CancellationToken cancellationToken = createCancellationToken(readRowsCall);

    int timeout = retryOptions.getReadPartialRowTimeoutMillis();

    ResponseQueueReader responseQueueReader =
        new ResponseQueueReader(timeout, streamingBufferSize, batchRequestSize,
            batchRequestSize, readRowsCall);

    StreamingBigtableResultScanner resultScanner =
        new StreamingBigtableResultScanner(responseQueueReader, cancellationToken);

    clientCallService.asyncServerStreamingCall(
        readRowsCall,
        request,
        createClientCallListener(resultScanner));

    if (batchRequestSize > 1) {
      readRowsCall.request(batchRequestSize - 1);
    }

    return resultScanner;
  }

  private CancellationToken createCancellationToken(final ClientCall<ReadRowsRequest, ReadRowsResponse> readRowsCall) {
    // If the scanner is closed before we're done streaming, we want to cancel the RPC.
    CancellationToken cancellationToken = new CancellationToken();
    cancellationToken.addListener(new Runnable() {
      @Override
      public void run() {
        readRowsCall.cancel();
      }
    }, executorService);
    return cancellationToken;
  }

  private ClientCall.Listener<ReadRowsResponse>
      createClientCallListener(final StreamingBigtableResultScanner resultScanner) {
    return new ClientCall.Listener<ReadRowsResponse>() {
      @Override
      public void onMessage(ReadRowsResponse readRowResponse) {
        resultScanner.addResult(readRowResponse);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        if (status.isOk()) {
          resultScanner.complete();
        } else {
          resultScanner.setError(status.asRuntimeException());
        }
      }
    };
  }
}
