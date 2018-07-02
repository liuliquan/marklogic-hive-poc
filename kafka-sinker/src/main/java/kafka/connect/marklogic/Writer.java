package kafka.connect.marklogic;

import java.io.Closeable;
import java.util.Collection;

import org.apache.kafka.connect.sink.SinkRecord;

/**
 * 
 * @author Sanju Thomas
 *
 */
public interface Writer extends Closeable {
	
    /**
     * Write records.
     * @param records the records to write
     */
	void write(final Collection<SinkRecord> records);

	/**
	 * Flush.
	 */
	void flush();
}
