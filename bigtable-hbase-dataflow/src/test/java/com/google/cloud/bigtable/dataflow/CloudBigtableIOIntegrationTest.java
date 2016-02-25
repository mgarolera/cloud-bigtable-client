package com.google.cloud.bigtable.dataflow;

import static org.mockito.Mockito.*;
import com.google.bigtable.v1.SampleRowKeysResponse;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.dataflow.CloudBigtableIO.Source;
import com.google.cloud.bigtable.hbase1_0.BigtableConnection;
import com.google.cloud.dataflow.sdk.io.BoundedSource;
import com.google.cloud.dataflow.sdk.io.BoundedSource.BoundedReader;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.DoFnTester;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CloudBigtableIOIntegrationTest {
  private static final String BIGTABLE_PROJECT_KEY = "google.bigtable.project.id";
  private static final String BIGTABLE_ZONE_KEY = "google.bigtable.zone.name";
  private static final String BIGTABLE_CLUSTER_KEY = "google.bigtable.cluster.name";

  public static final byte[] COLUMN_FAMILY = Bytes.toBytes("test_family");
  public static final byte[] QUALIFIER1 = Bytes.toBytes("qualifier1");

  private static final Logger LOG = new Logger(CloudBigtableIOIntegrationTest.class);

  private static String projectId = System.getProperty(BIGTABLE_PROJECT_KEY);
  private static String zoneId = System.getProperty(BIGTABLE_ZONE_KEY);
  private static String clusterId = System.getProperty(BIGTABLE_CLUSTER_KEY);

  private static int LARGE_VALUE_SIZE = 201326;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  public static TableName newTestTableName() {
    return TableName.valueOf("test-dataflow-" + UUID.randomUUID().toString());
  }

  private static TableName createNewTable(Admin admin) throws IOException {
    TableName tableName = newTestTableName();
    admin.createTable(
      new HTableDescriptor(tableName).addFamily(new HColumnDescriptor(COLUMN_FAMILY)));
    return tableName;
  }

  private static BigtableConnection connection;
  private static CloudBigtableConfiguration config;

  @BeforeClass
  public static void setup() throws IOException {
    config =
        new CloudBigtableConfiguration.Builder()
            .withProjectId(projectId)
            .withZoneId(zoneId)
            .withClusterId(clusterId)
            .build();
    connection = new BigtableConnection(config.toHBaseConfig());
  }

  @AfterClass
  public static void shutdown() throws IOException {
    connection.close();
  }

  private static CloudBigtableTableConfiguration createTableConfig(String tableId) {
    return new CloudBigtableTableConfiguration(projectId,
        zoneId, clusterId, tableId, Collections.<String, String>emptyMap());
  }

  @Test
  public void testWriteToTable_tableDoesNotExist() throws Exception {
    LOG.info("Testing testWriteToTable_tableDoesNotExist()");
    // The table doesn't exist.
    expectedException.expect(IllegalStateException.class);
    CloudBigtableIO.writeToTable(createTableConfig("does-not-exist-table")).validate(null);
  }

  @Test
  public void testWriteToTable_tableExist() throws Exception {
    try (Admin admin = connection.getAdmin()) {
      LOG.info("Creating table in testWriteToTable_tableExist()");
      TableName tableName = createNewTable(admin);
      try {
        CloudBigtableIO.writeToTable(createTableConfig(tableName.getNameAsString())).validate(null);
      } finally {
        admin.deleteTable(tableName);
      }
    }
  }

  @Test
  public void testWriteToTable_dataWrittenSerial() throws Exception {
    final int INSERT_COUNT = 50;
    try (Admin admin = connection.getAdmin()) {
      TableName tableName = createNewTable(admin);
      DoFn<Mutation, Void> writer =
        new CloudBigtableIO.CloudBigtableSingleTableSerialWriteFn(
            createTableConfig(tableName.getNameAsString()));

      try {
        writeThroughDataflow(writer, INSERT_COUNT);
        checkTableRowCount(tableName, INSERT_COUNT);
      } finally {
        admin.deleteTable(tableName);
      }
    }
  }

  @Test
  public void testWriteToTable_dataWrittenBuffered() throws Exception {
    final int INSERT_COUNT = 50;
    try (Admin admin = connection.getAdmin()) {
      TableName tableName = createNewTable(admin);
      DoFn<Mutation, Void> writer =
        new CloudBigtableIO.CloudBigtableSingleTableBufferedWriteFn(
            createTableConfig(tableName.getNameAsString()));

      try {
        writeThroughDataflow(writer, INSERT_COUNT);
        checkTableRowCount(tableName, INSERT_COUNT);
      } finally {
        admin.deleteTable(tableName);
      }
    }
  }

  private void writeThroughDataflow(DoFn<Mutation, Void> writer, int insertCount) throws Exception {
    DoFnTester<Mutation, Void> fnTester = DoFnTester.of(writer);
    for (int i = 0; i < insertCount; i++) {
      byte[] row = Bytes.toBytes("row_" + i);
      Mutation mutation =  new Put(row).addColumn(COLUMN_FAMILY, QUALIFIER1,
          Bytes.toBytes(RandomStringUtils.randomAlphanumeric(8)));

      fnTester.processBatch(mutation);
    }
  }

  private void checkTableRowCount(TableName tableName, int rowCount) throws IOException {
    int readCount = 0;
    try (Table table = connection.getTable(tableName);
        ResultScanner scanner = table.getScanner(new Scan())) {
      while (scanner.next() != null) {
        readCount++;
      }
    }
    Assert.assertEquals(rowCount, readCount);
  }

  @Test
  public void testReadFromTable_singleResultDataflowReader() throws Exception {
    final int INSERT_COUNT = 50;
    try (Admin admin = connection.getAdmin()) {
      TableName tableName = createNewTable(admin);
      try {
        writeViaTable(tableName, INSERT_COUNT);
        checkTableRowCountViaDataflowResultReader(tableName, INSERT_COUNT);
      } finally {
        admin.deleteTable(tableName);
      }
    }
  }

  private void writeViaTable(TableName tableName, int rowCount) throws IOException {
    List<Put> puts = new ArrayList<>();
    for (int i = 0; i < rowCount; i++) {
      byte[] row = Bytes.toBytes("row_" + i);
      puts.add(new Put(row).addColumn(COLUMN_FAMILY, QUALIFIER1,
        Bytes.toBytes(RandomStringUtils.randomAlphanumeric(8))));
    }
    try (Table t = connection.getTable(tableName);) {
      t.put(puts);
    }
  }

  private void checkTableRowCountViaDataflowResultReader(TableName tableName, int rowCount) throws Exception {
    CloudBigtableScanConfiguration configuration = new CloudBigtableScanConfiguration(projectId,
        zoneId, clusterId, tableName.getNameAsString(), new Scan());
    BoundedSource<Result> source = CloudBigtableIO.read(configuration);
    List<? extends BoundedSource<Result>> splits = source.splitIntoBundles(1 << 20, null);
    int count = 0;
    for (BoundedSource<Result> sourceWithKeys : splits) {
      BoundedReader<Result> reader = sourceWithKeys.createReader(null);
      reader.start();
      while (reader.getCurrent() != null) {
        count++;
        reader.advance();
      }
    }
    Assert.assertEquals(rowCount, count);
  }

  @Test
  public void testReadFromTable_resultArrayDataflowReader() throws Exception {
    int INSERT_COUNT = 50;
    try (Admin admin = connection.getAdmin()) {
      TableName tableName = createNewTable(admin);
      try {
        writeViaTable(tableName, INSERT_COUNT);
        checkTableRowCountViaDataflowResultArrayReader(tableName, INSERT_COUNT);
      } finally {
        admin.deleteTable(tableName);
      }
    }
  }

  private void checkTableRowCountViaDataflowResultArrayReader(TableName tableName, int rowCount) throws Exception {
    CloudBigtableScanConfiguration configuration = new CloudBigtableScanConfiguration(projectId,
        zoneId, clusterId, tableName.getNameAsString(), new Scan());
    int batchCount = 10;
    BoundedSource<Result[]> source = CloudBigtableIO.readBulk(configuration, batchCount);
     List<? extends BoundedSource<Result[]>> splits = source.splitIntoBundles(1 << 20, null);
    int count = 0;
    int maxBatch = 0;
    for (BoundedSource<Result[]> sourceWithKeys : splits) {
      BoundedReader<Result[]> reader = sourceWithKeys.createReader(null);
      reader.start();
      while (reader.getCurrent().length != 0) {
        int length = reader.getCurrent().length;
        count += length;
        maxBatch = Math.max(maxBatch, length);
        reader.advance();
      }
    }
    Assert.assertEquals(rowCount, count);
    Assert.assertEquals(batchCount, maxBatch);
  }

  @Test
  public void testEstimatedAndSplitForSmallTable() throws Exception {
    try (Admin admin = connection.getAdmin()) {
      LOG.info("Creating table in testEstimatedAndSplitForSmallTable()");
      TableName tableName = createNewTable(admin);
      try (Table table = connection.getTable(tableName)) {
        table.put(Arrays.asList(
          new Put(Bytes.toBytes("row1")).addColumn(COLUMN_FAMILY, QUALIFIER1, Bytes.toBytes("1")),
          new Put(Bytes.toBytes("row2")).addColumn(COLUMN_FAMILY, QUALIFIER1, Bytes.toBytes("2"))));
      }

      LOG.info("getSampleKeys() in testEstimatedAndSplitForSmallTable()");

      try {
        CloudBigtableScanConfiguration config =
            new CloudBigtableScanConfiguration(projectId, zoneId, clusterId,
                tableName.getQualifierAsString(), new Scan());
        CloudBigtableIO.Source<Result> source = (Source<Result>) CloudBigtableIO.read(config);
        List<SampleRowKeysResponse> sampleRowKeys = source.getSampleRowKeys();
        LOG.info("Creating BoundedSource in testEstimatedAndSplitForSmallTable()");
        long estimatedSizeBytes = source.getEstimatedSizeBytes(null);
        SampleRowKeysResponse lastSample = sampleRowKeys.get(sampleRowKeys.size() - 1);
        Assert.assertEquals(lastSample.getOffsetBytes(), estimatedSizeBytes);

        LOG.info("Creating Bundles in testEstimatedAndSplitForSmallTable()");
        List<? extends BoundedSource<Result>> bundles =
            source.splitIntoBundles(estimatedSizeBytes / 2 + 1, null);
        // This will be a small table with no splits, so we return HConstants.EMPTY_START_ROW
        // which can't be split.
        LOG.info("Created Bundles in testEstimatedAndSplitForSmallTable()");
        Assert.assertEquals(sampleRowKeys.size() * 2 - 1, bundles.size());
        Assert.assertSame(sampleRowKeys, source.getSampleRowKeys());
      } finally {
        LOG.info("Deleting table in testEstimatedAndSplitForSmallTable()");
        admin.deleteTable(tableName);
        LOG.info("Deleted table in testEstimatedAndSplitForSmallTable()");
      }
    }
  }

  @Test
  public void testEstimatedAndSplitForLargeTable() throws Exception {
    try (Admin admin = connection.getAdmin()) {
      LOG.info("Creating table in testEstimatedAndSplitForLargeTable()");
      TableName tableName = createNewTable(admin);

      final int rowCount = 1000;
      LOG.info("Adding %d rows in testEstimatedAndSplitForLargeTable()", rowCount);
      try (BufferedMutator mutator = connection.getBufferedMutator(tableName)) {
        for (int i = 0; i < rowCount; i++ ) {
          byte[] largeValue = Bytes.toBytes(RandomStringUtils.randomAlphanumeric(LARGE_VALUE_SIZE));
          mutator.mutate(new Put(Bytes.toBytes("row" + i)).addColumn(COLUMN_FAMILY, QUALIFIER1,
            largeValue));
        }
      }

      try {
        LOG.info("Getting Source in testEstimatedAndSplitForLargeTable()");
        CloudBigtableScanConfiguration config =
            new CloudBigtableScanConfiguration(projectId, zoneId, clusterId,
                tableName.getQualifierAsString(), new Scan());
        CloudBigtableIO.Source<Result> source = (Source<Result>) CloudBigtableIO.read(config);
        List<SampleRowKeysResponse> sampleRowKeys = source.getSampleRowKeys();
        LOG.info("Getting estimated size in testEstimatedAndSplitForLargeTable()");
        long estimatedSizeBytes = source.getEstimatedSizeBytes(null);
        SampleRowKeysResponse lastSample = sampleRowKeys.get(sampleRowKeys.size() - 1);
        Assert.assertEquals(lastSample.getOffsetBytes(), estimatedSizeBytes);

        LOG.info("Getting Bundles in testEstimatedAndSplitForLargeTable()");
        List<? extends BoundedSource<Result>> bundles =
            source.splitIntoBundles(sampleRowKeys.get(0).getOffsetBytes() / 2, null);
        // The last sample includes the EMPTY_END_ROW key, which cannot be split.
        Assert.assertEquals(sampleRowKeys.size() * 2 - 1, bundles.size());
        final AtomicInteger count = new AtomicInteger();
        LOG.info("Reading Bundles in testEstimatedAndSplitForLargeTable()");
        ExecutorService es = Executors.newCachedThreadPool();
        try {
          for (final BoundedSource<Result> bundle : bundles) {
            es.submit(new Runnable() {
              @Override
              public void run() {
                try (BoundedReader<Result> reader = bundle.createReader(null)) {
                  reader.start();
                  while (reader.getCurrent() != null) {
                    count.incrementAndGet();
                    reader.advance();
                  }
                } catch (IOException e) {
                  LOG.warn("Could not read bundle: %s", e, bundle);
                }
              }
            });
          }
        } finally {
          LOG.info("Shutting down executor in testEstimatedAndSplitForLargeTable()");
          es.shutdown();
          while (!es.isTerminated()) {
            es.awaitTermination(1, TimeUnit.SECONDS);
          }
        }
        Assert.assertSame(sampleRowKeys, source.getSampleRowKeys());
        Assert.assertEquals(rowCount, count.intValue());
      } finally {
        LOG.info("Deleting table in testEstimatedAndSplitForLargeTable()");
        admin.deleteTable(tableName);
      }
    }
  }
}
