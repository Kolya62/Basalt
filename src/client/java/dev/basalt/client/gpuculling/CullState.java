package dev.basalt.client.gpuculling;

/** Mixed into RenderSection: latest GPU occlusion result (true = fully hidden last time it was tested). */
public interface CullState {
	boolean basalt$occluded();

	void basalt$setOccluded(boolean occluded);
}
