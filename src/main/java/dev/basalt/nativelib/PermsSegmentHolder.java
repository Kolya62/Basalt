package dev.basalt.nativelib;

import java.lang.foreign.MemorySegment;

/** Mixed into PerlinNoise: its permutation table as a heap segment, created once per noise instance. */
public interface PermsSegmentHolder {
	MemorySegment basalt$permsSegment();
}
