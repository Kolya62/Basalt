package dev.basalt.chunklevel;

/** Immutable (key, level) pair, so a racing reader can never see a key with another key's level. */
public record LevelEntry(long key, int level) {
}
