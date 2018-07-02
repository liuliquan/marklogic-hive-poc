package kafka.connect.marklogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.hcatalog.common.HCatConstants;
import org.apache.hive.hcatalog.streaming.HiveEndPoint;
import org.apache.hive.hcatalog.streaming.SerializationError;
import org.apache.hive.hcatalog.streaming.StreamingConnection;
import org.apache.hive.hcatalog.streaming.StreamingException;
import org.apache.hive.hcatalog.streaming.StrictJsonWriter;
import org.apache.hive.hcatalog.streaming.TransactionBatch;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Write to Hive in batch using Hive Streaming API.
 * @author TCSCODER
 * @version 1.9
 */
public class HiveWriter {
    private static final Logger logger = LoggerFactory.getLogger(HiveWriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int batchSize;
    private final int maxRetires;
    private final int retryBackoff;
    private final String hiveThriftUrl;

    private final ExecutorService flushExecutors = Executors.newFixedThreadPool(3);

    private Batch positionBatch;
    private Batch instrumentBatch;
    private Batch transactionBatch;

    public HiveWriter(String hiveThriftUrl, int batchSize, int maxRetires, int retryBackoff) {
        this.batchSize = batchSize;
        this.maxRetires = maxRetires;
        this.retryBackoff = retryBackoff;
        this.hiveThriftUrl = hiveThriftUrl;
    }

    public void start() {
        try {
            positionBatch = new Batch("position");
            instrumentBatch = new Batch("instrument");
            transactionBatch = new Batch("transaction");
        } catch (StreamingException | InterruptedException e) {
            logger.error("Failed to start Hive batch writer", e);
            throw new RuntimeException("Failed to start Hive batch writer", e);
        }
    }

    public void add(SinkRecord r) {
        if (r.topic().equalsIgnoreCase("position")) {
            positionBatch.add(r);
        } else if (r.topic().equalsIgnoreCase("instrument")) {
            instrumentBatch.add(r);
        } else if (r.topic().equalsIgnoreCase("transaction")) {
            transactionBatch.add(r);
        }
    }

    public void flushAndWait() {
        Future<?> positionFuture = flushExecutors.submit(new Runnable() {
            
            @Override
            public void run() {
                positionBatch.flushAndWait();
            }
        });
        Future<?> instrumentFuture = flushExecutors.submit(new Runnable() {
            
            @Override
            public void run() {
                instrumentBatch.flushAndWait();
            }
        });
        Future<?> transactionFuture = flushExecutors.submit(new Runnable() {
            
            @Override
            public void run() {
                transactionBatch.flushAndWait();
            }
        });

        try {
            positionFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to wait position batch flush", e);
        }
        try {
            instrumentFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to wait instrument batch flush", e);
        }
        try {
            transactionFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to wait transaction batch flush", e);
        }

        long totalCount = positionBatch.totalCount;
        totalCount += instrumentBatch.totalCount;
        totalCount += transactionBatch.totalCount;
        logger.info("Hive: written total {} records", totalCount);
    }

    private static enum BatchAction {
        WRITE,
        RETRY,
        HEARTBEAT,
    }

    private class Batch {

        // Limit the queue's capacity, don't let it grow too large
        private final BlockingQueue<Map<?, ?>> queue = new LinkedBlockingQueue<>(10000);

        private final String table;
        private final HiveEndPoint endpoint;
        private final StreamingConnection connection;

        private TransactionBatch txnBatch;
        private final List<byte[]> writeBatch = new ArrayList<>();

        private long totalCount = 0;

        public Batch(String table) throws StreamingException, InterruptedException {
            this.table = table;
            this.endpoint = new HiveEndPoint(hiveThriftUrl, "default", table, null);

            HiveConf conf = new HiveConf();
            // This is very important to disable cached client in order to ensure thread safety
            conf.setBoolean(HCatConstants.HCAT_HIVE_CLIENT_DISABLE_CACHE, true);

            this.connection = this.endpoint.newConnection(true, conf, table + "-writer");
            this.txnBatch = this.connection.fetchTransactionBatch(10,
                    new StrictJsonWriter(this.endpoint, this.connection));
            this.txnBatch.beginNextTransaction();

            // Drain records from queue, write to Hive
            new Thread(new Runnable() {
                @Override
                public void run() {
                    List<Map<?, ?>> drain = new ArrayList<>();
                    while (true) {
                        queue.drainTo(drain, Math.max(1, batchSize - writeBatch.size()));
                        for (Map<?, ?> record : drain) {
                            try {
                                performAction(record, batchSize, BatchAction.WRITE);
                            } catch (InterruptedException e) {
                                // Thread interrupted, return
                                return;
                            }
                        }
                        drain.clear();
                    }
                }
            }).start();

            // Heartbeat to keep unused transactions alive
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while(true) {
                        try {
                            Thread.sleep(60 * 1000);
                            performAction(null, 0, BatchAction.HEARTBEAT);
                        } catch (InterruptedException e1) {
                            // Thread interrupted, return
                            return;
                        }
                    }
                }
            }).start();
        }

        public void add(SinkRecord r) {
            Map<?, ?> record = (Map<?, ?>) r.value();

            try {
                queue.offer(record, Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Thread interrupted while adding record: " + record, e);
            }
        }

        public void flushAndWait() {
            try {
                performAction(null, 1, BatchAction.WRITE);
            } catch (InterruptedException e) {
                // Ignore
            }
        }

        private synchronized void performAction(Map<?, ?> record, int flushSize, BatchAction action) throws InterruptedException {
            if (action == BatchAction.HEARTBEAT) {
                try {
                    txnBatch.heartbeat();
                } catch (StreamingException e) {
                    // Log and ignore heartbeat error
                    logger.error("Failed to send transaction heart beat to Hive", e);
                }
                return;
            }

            try {
                if (record != null) {
                    byte[] bytes;
                    try {
                        bytes = MAPPER.writeValueAsBytes(record);
                    } catch (JsonProcessingException e) {
                        // Log and ignore
                        logger.error("Failed to format record to JSON: " + record, e);
                        return;
                    }

                    writeBatch.add(bytes);
                    txnBatch.write(bytes);
                }

                if (writeBatch.size() >= flushSize) {
                    if (action == BatchAction.RETRY) {
                        for (byte[] bytes : writeBatch) {
                            txnBatch.write(bytes);
                        }
                    }

                    txnBatch.commit();
                    totalCount += writeBatch.size();
                    writeBatch.clear();

                    logger.info("Hive: written {} records to table: " + table, totalCount);

                    if (txnBatch.remainingTransactions() > 0) {
                        txnBatch.beginNextTransaction();
                    } else {
                        txnBatch.close();
                        logger.info("Fetch new transactions batch for table: " + table);
                        txnBatch = connection.fetchTransactionBatch(10, new StrictJsonWriter(endpoint, connection));
                        txnBatch.beginNextTransaction();
                    }
                }

            } catch (InterruptedException | StreamingException e) {
                if (e instanceof SerializationError) {
                    // Log and ignore SerializationError
                    logger.warn("SerializationError when write to Hive table: " + table, e);
                    return;
                }

                // Abort transaction and close batch
                abortAndCloseBatch();

                if (e instanceof InterruptedException) {
                    // Log and re-throw InterruptedException
                    logger.error("Batch write to Hive thread interrupted: " + table, e);
                    throw (InterruptedException) e;
                }

                if (action == BatchAction.RETRY) {
                    throw new RetriableException(e);
                }

                // Retry writing
                int remainingRetries = Math.max(1, maxRetires);
                long sleepTime = 0;

                while (remainingRetries > 0) {
                    try {
                        // Retry with new transaction batch
                        logger.info("Will retry batch write to Hive table: " + table);
                        txnBatch = connection.fetchTransactionBatch(10, new StrictJsonWriter(endpoint, connection));
                        txnBatch.beginNextTransaction();
                        performAction(null, 1, BatchAction.RETRY);
                        return;
                    } catch (InterruptedException | StreamingException | RetriableException re) {
                        logger.error("Retry batch write to Hive failed", re);

                        abortAndCloseBatch();

                        if (re instanceof InterruptedException) {
                            // Log and re-throw InterruptedException
                            logger.error("Retry batch write to Hive thread interrupted: " + table, re);
                            throw (InterruptedException) re;
                        }

                        remainingRetries--;
                        if (remainingRetries > 0) {
                            sleepTime += retryBackoff;
                            logger.info("Will retry batch write to Hive in {} seconds", sleepTime / 1000);
                            try {
                                Thread.sleep(sleepTime);
                            } catch (InterruptedException e2) {
                                // Log and re-throw InterruptedException
                                logger.error("Retry batch write to Hive waiting thread interrupted", e2);
                                throw (InterruptedException) e2;
                            }
                        } else {
                            logger.error("Retry batch write to Hive exhausted", re);
                        }
                    }
                }
            }
        }

        private void abortAndCloseBatch() {
            // Abort current open transaction
            try {
                txnBatch.abort();
            } catch (Exception e1) {
                logger.error("Failed to abort Hive transaction batch", e1);
            }

            // Close transaction batch
            try {
                txnBatch.close();
            } catch (Exception e1) {
                logger.error("Failed to close Hive transaction batch", e1);
            }
        }

        public void close() {
            this.connection.close();
        }
    }

    public void close() {
        flushExecutors.shutdown();

        try {
            flushExecutors.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }

        positionBatch.close();
        instrumentBatch.close();
        transactionBatch.close();
    }
}
