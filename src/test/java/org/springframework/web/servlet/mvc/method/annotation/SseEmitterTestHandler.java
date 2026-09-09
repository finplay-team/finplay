package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.http.MediaType;

public final class SseEmitterTestHandler implements ResponseBodyEmitter.Handler {

	private final List<Runnable> completionCallbacks = new ArrayList<>();
	private final List<Runnable> timeoutCallbacks = new ArrayList<>();
	private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();
	private final List<Object> sentEvents = new CopyOnWriteArrayList<>();

	private boolean throwIoExceptionOnSend = false;
	private boolean completeWithErrorCalled = false;
	private volatile long sendDelayMillis = 0;

	public void attachTo(ResponseBodyEmitter emitter) throws IOException {
		emitter.initialize(this);
	}

	public void failOnNextSend() {
		this.throwIoExceptionOnSend = true;
	}

	public void delaySendsBy(long millis) {
		this.sendDelayMillis = millis;
	}

	private void applySendDelay() throws IOException {
		if (sendDelayMillis <= 0) {
			return;
		}
		try {
			Thread.sleep(sendDelayMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("send delay interrupted", e);
		}
	}

	public boolean isCompleteWithErrorCalled() {
		return completeWithErrorCalled;
	}

	public List<Object> getSentEvents() {
		return sentEvents;
	}

	public void triggerCompletion() {
		completionCallbacks.forEach(Runnable::run);
	}

	public void triggerTimeout() {
		timeoutCallbacks.forEach(Runnable::run);
	}

	public void triggerError(Throwable throwable) {
		errorCallbacks.forEach(callback -> callback.accept(throwable));
	}

	@Override
	public void send(Object data, MediaType mediaType) throws IOException {
		applySendDelay();
		if (throwIoExceptionOnSend) {
			throw new IOException("simulated broken connection");
		}
		sentEvents.add(data);
	}

	@Override
	public void send(Set<ResponseBodyEmitter.DataWithMediaType> dataToSend) throws IOException {
		applySendDelay();
		if (throwIoExceptionOnSend) {
			throw new IOException("simulated broken connection");
		}
		sentEvents.addAll(dataToSend);
	}

	@Override
	public void complete() {}

	@Override
	public void completeWithError(Throwable failure) {
		this.completeWithErrorCalled = true;
	}

	@Override
	public void onTimeout(Runnable callback) {
		timeoutCallbacks.add(callback);
	}

	@Override
	public void onError(Consumer<Throwable> callback) {
		errorCallbacks.add(callback);
	}

	@Override
	public void onCompletion(Runnable callback) {
		completionCallbacks.add(callback);
	}
}
