package dev.basalt.client.modelpart;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Shares equal entity model vertices (and equal polygon vertex arrays) while models are baked.
 * The maps only live for one resource reload and are cleared when it finishes: a map node costs
 * about as much as the vertex it dedupes, so keeping them would eat most of the saving, and they
 * would otherwise grow with every reload.
 */
public final class VertexInterner {
	private static final ConcurrentHashMap<ModelPart.Vertex, ModelPart.Vertex> VERTICES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<List<ModelPart.Vertex>, ModelPart.Vertex[]> ARRAYS = new ConcurrentHashMap<>();

	private VertexInterner() {
	}

	/** Replaces the array's vertices with shared equal ones and returns a shared equal array (possibly this one). */
	public static ModelPart.Vertex[] intern(ModelPart.Vertex[] own) {
		for (int i = 0; i < own.length; i++) {
			ModelPart.Vertex v = own[i];
			ModelPart.Vertex shared = VERTICES.putIfAbsent(v, v);
			if (shared != null) {
				own[i] = shared;
			}
		}
		ModelPart.Vertex[] sharedArray = ARRAYS.putIfAbsent(Arrays.asList(own), own);
		return sharedArray != null ? sharedArray : own;
	}

	/** End of a resource reload: the baked models keep their shared instances, the maps go. */
	public static void clear() {
		VERTICES.clear();
		ARRAYS.clear();
	}
}
