package dev.basalt.client.modelpart;

import org.joml.Vector3f;

/** Per-thread scratch for Cube.compile so it allocates nothing per cube. */
public final class CubeScratch {
	private static final ThreadLocal<CubeScratch> LOCAL = ThreadLocal.withInitial(CubeScratch::new);
	public final Vector3f vec = new Vector3f();
	public final org.joml.Quaternionf quat = new org.joml.Quaternionf();
	private float[] floats = new float[24];

	/** The render thread gets a plain field; ThreadLocal lookups showed up at 7% of a frame. */
	private static volatile Thread owner;
	private static final CubeScratch OWNER_SCRATCH = new CubeScratch();

	public static CubeScratch get() {
		Thread t = Thread.currentThread();
		Thread o = owner;
		if (o == t) {
			return OWNER_SCRATCH;
		}
		if (o == null && "Render thread".equals(t.getName())) {
			synchronized (CubeScratch.class) {
				if (owner == null) {
					owner = t;
					return OWNER_SCRATCH;
				}
			}
		}
		return LOCAL.get();
	}

	public float[] floats(int n) {
		if (this.floats.length < n) {
			this.floats = new float[n];
		}
		return this.floats;
	}
}
