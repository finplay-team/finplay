package com.finplay.api.domain.feedback.service;

import org.springframework.stereotype.Component;

@Component
public class LlmCallStats {

	private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

	public void startScope() {
		CURRENT.set(new Counter());
	}

	public Snapshot finishScope() {
		Counter counter = CURRENT.get();
		CURRENT.remove();
		return counter == null ? Snapshot.EMPTY : new Snapshot(counter.count, counter.totalNanos);
	}

	public void record(long elapsedNanos) {
		Counter counter = CURRENT.get();
		if (counter == null) {
			return;
		}
		counter.count++;
		counter.totalNanos += elapsedNanos;
	}

	public record Snapshot(long count, long totalNanos) {

		static final Snapshot EMPTY = new Snapshot(0L, 0L);

		public long totalMillis() {
			return totalNanos / 1_000_000L;
		}
	}

	private static final class Counter {

		private long count;

		private long totalNanos;
	}
}
