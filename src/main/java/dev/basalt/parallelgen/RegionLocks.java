package dev.basalt.parallelgen;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

/**
 * All-or-nothing locks on sets of chunk positions. A request either gets every position at once
 * or waits, so no request ever holds part of what it needs and deadlock is impossible. Waiters
 * are granted in arrival order whenever their whole set becomes free.
 */
public final class RegionLocks {
	private final LongOpenHashSet held = new LongOpenHashSet();
	private final ArrayDeque<Request> waiting = new ArrayDeque<>();

	private record Request(long[] keys, CompletableFuture<Void> granted) {
	}

	public synchronized CompletableFuture<Void> acquire(long[] keys) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		if (this.waiting.isEmpty() && this.free(keys)) {
			this.take(keys);
			future.complete(null);
		} else {
			this.waiting.add(new Request(keys, future));
		}
		return future;
	}

	public void release(long[] keys) {
		ArrayDeque<CompletableFuture<Void>> toGrant = new ArrayDeque<>();
		synchronized (this) {
			for (long key : keys) {
				this.held.remove(key);
			}
			for (Iterator<Request> it = this.waiting.iterator(); it.hasNext(); ) {
				Request r = it.next();
				if (this.free(r.keys)) {
					this.take(r.keys);
					it.remove();
					toGrant.add(r.granted);
				}
			}
		}
		// complete outside the monitor: completion may run the dependent work inline
		toGrant.forEach(f -> f.complete(null));
	}

	public synchronized int heldCount() {
		return this.held.size();
	}

	private boolean free(long[] keys) {
		for (long key : keys) {
			if (this.held.contains(key)) {
				return false;
			}
		}
		return true;
	}

	private void take(long[] keys) {
		for (long key : keys) {
			this.held.add(key);
		}
	}
}
