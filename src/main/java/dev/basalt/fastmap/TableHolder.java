package dev.basalt.fastmap;

/** Mixed into StateHolder. */
public interface TableHolder {
	/** Verifies the table against this state's vanilla arrays, then switches to it and drops them. Returns false on mismatch. */
	boolean basalt$compact(StateTable table, int index);
}
