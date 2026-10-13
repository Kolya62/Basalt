package dev.basalt.client.modelpart;

/** Mixed into BufferBuilder: reserve several ENTITY-format vertices at once. */
public interface BulkVertexSink {
	/**
	 * If this builder is an ENTITY-format, non-LINES BufferBuilder, reserves {@code count} vertices
	 * exactly as {@code count} calls of the vanilla fast path would and returns the first vertex's
	 * address; otherwise returns -1 and the caller must use addVertex.
	 */
	long basalt$reserveEntityVertices(int count);
}
