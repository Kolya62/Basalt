package dev.basalt.client.modelpart;

/**
 * map: flattened polygon vertex -> index into unique (x,y,z triples). One object with final
 * fields, so a lazily built index is safely visible to any thread that renders models.
 */
public record CubeIndex(int[] map, float[] unique) {
}
