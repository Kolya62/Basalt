package dev.basalt.poi;

/** Mixed into SectionStorage: which chunk columns are unpacked, and which contain any section. */
public interface ColumnIndex {
	/** True only if the column is unpacked and every section in it is absent (vanilla would return nothing). */
	boolean basalt$isColumnKnownEmpty(long chunkKey);
}
