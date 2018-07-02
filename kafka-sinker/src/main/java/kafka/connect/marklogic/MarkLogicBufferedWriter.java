package kafka.connect.marklogic;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.WriteBatcher;
import com.marklogic.client.io.DocumentMetadataHandle;

import kafka.connect.marklogic.sink.MarkLogicSinkConfig;

/**
 *
 * @author Sanju Thomas
 *
 */
public class MarkLogicBufferedWriter extends MarkLogicWriter implements Writer{

    private static final Logger logger = LoggerFactory.getLogger(MarkLogicBufferedWriter.class);

    private final ExecutorService flushExecutors = Executors.newFixedThreadPool(2);
    private final DataMovementManager manager;
    private final WriteBatcher batcher;
    private final HiveWriter hiveWriter;

    public MarkLogicBufferedWriter(final Map<String, String> config){
	    super(config);
	    manager = super.client.newDataMovementManager();
	    batcher = manager.newWriteBatcher();

        final int batchSize = Integer.valueOf(config.get(MarkLogicSinkConfig.BATCH_SIZE));
	    final int maxRetires = Integer.valueOf(config.get(MarkLogicSinkConfig.MAX_RETRIES));
        final int retryBackoff = Integer.valueOf(config.get(MarkLogicSinkConfig.RETRY_BACKOFF_MS));

        hiveWriter = new HiveWriter(config.get(MarkLogicSinkConfig.HIVE_THRIFT_URL), batchSize, maxRetires, retryBackoff);
        hiveWriter.start();

        batcher.withBatchSize(batchSize).withThreadCount(1).onBatchSuccess(batch -> {
            logger.info("MarkLogic: written total {} records", batch.getJobWritesSoFar());
        });

	    batcher.setBatchFailureListeners((b, t) -> {
            logger.error("Batch write to MarkLogic failed, will retry", t);
            int remainingRetries = maxRetires;
            long sleepTime = 0;
            while (remainingRetries > 0) {
                sleepTime += retryBackoff;
                logger.info("Will retry batch write to MarkLogic in {} seconds", sleepTime / 1000);
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    logger.error("Retry batch write to MarkLogic thread interrupted", e);
                }
                try {
                    batcher.retry(b);
                    return;
                } catch (Throwable rt) {
                    logger.error("Retry batch write to MarkLogic failed", rt);
                    remainingRetries--;
                }
            }
            throw new RetriableException("Retry batch write to MarkLogic exhausted", t);
        });
        manager.startJob(batcher);
	}

    @Override
    public void write(final Collection<SinkRecord> recrods) {
        recrods.forEach(r -> {
            Map<?, ?> v = new LinkedHashMap<>((Map<?, ?>) r.value());

            v.remove("type");

            Object hiveObj = v.remove("hive");
            boolean hive = hiveObj == null ? false : Boolean.parseBoolean(hiveObj.toString());

            String url = super.url(v);

            if (hive) {
                hiveWriter.add(r);
            } else {
                DocumentMetadataHandle metadata = new DocumentMetadataHandle();
                metadata.getCollections().addAll(r.topic());
                batcher.add(url, metadata, super.handle(v));
            }
        });
    }

    @Override
    public void flush() {
        Future<?> marklogicFuture = flushExecutors.submit(new Runnable() {
            
            @Override
            public void run() {
                batcher.flushAndWait();
            }
        });
        Future<?> hiveFuture = flushExecutors.submit(new Runnable() {
            
            @Override
            public void run() {
                hiveWriter.flushAndWait();
            }
        });
        try {
            marklogicFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to wait marklogic batch flush", e);
        }
        try {
            hiveFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Failed to wait hive batch flush", e);
        }
    }

    @Override
    public void close() {
        flushExecutors.shutdown();

        try {
            flushExecutors.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
        }

        hiveWriter.close();

        batcher.awaitCompletion();
        manager.stopJob(batcher);
        manager.release();
        super.close();
    }

}
