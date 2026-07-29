// SseEmitterRegistry의 onCompletion·onTimeout·onError 콜백이 실제 Spring SSE 생명주기에서 동작하는지 검증하기 위한
// 테스트 지원 클래스. ResponseBodyEmitter.Handler는 패키지 접근 제한(package-private)이라 같은 패키지에 두어야
// 실제 프레임워크 콜백 등록·트리거 경로를 (mock이 아닌) 그대로 재현할 수 있다. src/main 코드는 아니며 테스트 전용이다.
package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.http.MediaType;

public final class SseEmitterTestHandler implements ResponseBodyEmitter.Handler {

	private final List<Runnable> completionCallbacks = new ArrayList<>();
	private final List<Runnable> timeoutCallbacks = new ArrayList<>();
	private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();
	private final List<Object> sentEvents = new ArrayList<>();

	private boolean throwIoExceptionOnSend = false;
	private boolean completeWithErrorCalled = false;

	// emitter를 이 핸들러로 초기화한다 — 프레임워크가 실제 요청 처리 시 수행하는 것과 동일한 진입점(package-private initialize)이다.
	public void attachTo(ResponseBodyEmitter emitter) throws IOException {
		emitter.initialize(this);
	}

	public void failOnNextSend() {
		this.throwIoExceptionOnSend = true;
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
		if (throwIoExceptionOnSend) {
			throw new IOException("simulated broken connection");
		}
		sentEvents.add(data);
	}

	@Override
	public void send(Set<ResponseBodyEmitter.DataWithMediaType> dataToSend) throws IOException {
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
