package dev.basalt.parallelgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * All-or-nothing locks on sets of chunk positions. A request either gets every position at once
 * or waits, so no request ever holds part of what it needs and deadlock is impossible.
 * <p>
 * Fairness is per key, not global: every key has a FIFO of waiters, and a request is granted only
 * when all its keys are free and it is first in line on each of them. A request whose keys nobody
 * holds or waits for is granted immediately, however long the queue elsewhere is. A waiter only
 * ever waits for running holders or older waiters, so there are no cycles and no starvation.
 */
public final class RegionLocks {
	private final LongOpenHashSet held = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<ArrayDeque<Request>> waiters = new Long2ObjectOpenHashMap<>();

	private record Request(long[] keys, CompletableFuture<Void> granted) {
	}

	public CompletableFuture<Void> acquire(long[] keys) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		synchronized (this) {
			if (this.free(keys) && this.noWaiters(keys)) {
				this.take(keys);
				future.complete(null); // nothing is attached yet, so nothing runs under the monitor
				return future;
			}
			Request request = new Request(keys, future);
			for (long key : keys) {
				this.waiters.computeIfAbsent(key, k -> new ArrayDeque<>(2)).addLast(request);
			}
		}
		return future;
	}

	public void release(long[] keys) {
		List<CompletableFuture<Void>> toGrant = null;
		synchronized (this) {
			for (long key : keys) {
				this.held.remove(key);
			}
			// only the first waiter on a released key can have become grantable
			for (long key : keys) {
				ArrayDeque<Request> queue = this.waiters.get(key);
				if (queue == null) {
					continue;
				}
				Request head = queue.peekFirst();
				if (this.free(head.keys) && this.firstInLine(head)) {
					this.take(head.keys);
					for (long k : head.keys) {
						ArrayDeque<Request> q = this.waiters.get(k);
						q.pollFirst();
						if (q.isEmpty()) {
							this.waiters.remove(k);
						}
					}
					if (toGrant == null) {
						toGrant = new ArrayList<>(2);
					}
					toGrant.add(head.granted);
				}
			}
		}
		// complete outside the monitor: completion runs the dependent work inline
		if (toGrant != null) {
			toGrant.forEach(f -> f.complete(null));
		}
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

	private boolean noWaiters(long[] keys) {
		for (long key : keys) {
			if (this.waiters.containsKey(key)) {
				return false;
			}
		}
		return true;
	}

	private boolean firstInLine(Request request) {
		for (long key : request.keys) {
			if (this.waiters.get(key).peekFirst() != request) {
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
