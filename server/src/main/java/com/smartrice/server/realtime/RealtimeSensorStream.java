package com.smartrice.server.realtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Long-poll waiting room for realtime sensor readings. A request that already holds the
 * newest sample is suspended until the serial collector commits a new one, so the dashboard
 * renders every change as it lands instead of refreshing on a fixed interval.
 */
@Service
public class RealtimeSensorStream {

	private static final int MAX_WAIT_SECONDS = 25;

	private final Map<String, List<Pending>> waiting = new LinkedHashMap<>();
	private final AsyncTaskExecutor executor;

	public RealtimeSensorStream(@Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor) {
		this.executor = executor;
	}

	/** Answers immediately when the client is behind; otherwise waits for the next sample. */
	public synchronized <T> DeferredResult<T> await(String stationId, boolean unchanged, int waitSeconds,
			Supplier<T> snapshot) {
		int wait = unchanged ? Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds)) : 0;
		if (wait == 0) {
			DeferredResult<T> immediate = new DeferredResult<>();
			immediate.setResult(snapshot.get());
			return immediate;
		}
		DeferredResult<T> result = new DeferredResult<>(wait * 1000L);
		Pending pending = new Pending(result, snapshot);
		waiting.computeIfAbsent(stationId, station -> new ArrayList<>()).add(pending);
		result.onTimeout(() -> expire(stationId, pending));
		result.onCompletion(() -> remove(stationId, pending));
		result.onError(error -> remove(stationId, pending));
		return result;
	}

	/** Wakes after the reading commits, so a released client never reads an uncommitted sample. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onReading(RealtimeReadingEvent event) {
		wake(event.stationId());
	}

	/**
	 * Runs on the serial collector thread, so it only hands the waiters off: building a snapshot
	 * queries the whole day and must never sit between two reads of the serial port.
	 */
	synchronized void wake(String stationId) {
		List<Pending> pending = waiting.remove(stationId);
		if (pending == null) {
			return;
		}
		for (Pending item : new ArrayList<>(pending)) {
			executor.execute(() -> complete(item));
		}
	}

	/** Completes outside the lock; a snapshot must never block the collector's wake-up. */
	private void expire(String stationId, Pending pending) {
		remove(stationId, pending);
		complete(pending);
	}

	private synchronized void remove(String stationId, Pending pending) {
		List<Pending> pendings = waiting.get(stationId);
		if (pendings == null) {
			return;
		}
		pendings.remove(pending);
		if (pendings.isEmpty()) {
			waiting.remove(stationId);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void complete(Pending pending) {
		DeferredResult result = pending.result();
		if (result.isSetOrExpired()) {
			return;
		}
		try {
			result.setResult(pending.snapshot().get());
		}
		catch (RuntimeException ex) {
			result.setErrorResult(ex);
		}
	}

	private record Pending(DeferredResult<?> result, Supplier<?> snapshot) {
	}

}
