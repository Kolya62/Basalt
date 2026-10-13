package dev.basalt.client.culling;

/** Mixed into Entity and BlockEntity. State is written by the culling thread, read by the render thread. */
public interface Cullable {
	boolean basalt$isCulled();

	void basalt$setCulled(boolean culled);

	/** Last time (ms) the render thread asked about this object; stale objects are not traced. */
	long basalt$lastRequested();

	void basalt$setLastRequested(long ms);

	/** Culling thread only: what the last trace saw (camera, box, world epoch) and when it ran. */
	long basalt$tracedKey();

	long basalt$tracedAt();

	void basalt$setTraced(long key, long ms);
}
