// SseEmitterRegistry의 onCompletion·onTimeout·onError 콜백이 실제 Spring SSE 생명주기에서 동작하는지 검증하기 위한
// 테스트 지원 클래스. ResponseBodyEmitter.Handler는 패키지 접근 제한(package-private)이라 같은 패키지에 두어야
// 실제 프레임워크 콜백 등록·트리거 경로를 (mock이 아닌) 그대로 재현할 수 있다. src/main 코드는 아니며 테스트 전용이다.
//
// 위험: 이 클래스는 package-private SPI(ResponseBodyEmitter.Handler)와 package-private 메서드
// (ResponseBodyEmitter.initialize(Handler))에 의존한다. Spring 마이너 업그레이드가 이 인터페이스에 메서드를
// 추가하면(과거 6.0.12에서 send(Set<DataWithMediaType>) 추가 이력 있음) 이 파일의 컴파일이 먼저 깨진다.
// 채택 근거: 이슈 #18 시점에는 SSE 엔드포인트 컨트롤러가 아직 없어(#19·#20 예정) MockMvc의
// asyncDispatch 경로로 완료·타임아웃·에러 콜백을 실제 트리거할 방법이 없었다 — 대안으로 이 핸들러를 채택했다.
// Spring 업그레이드로 컴파일이 깨지면, 신규 메서드 구현 추가 또는 컨트롤러가 생긴 뒤 MockMvc 기반 테스트로
// 교체하는 방안을 검토한다.
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
	// delaySendsBy()로 지연 전송을 쓰는 테스트는 리스너 스레드(쓰기)와 테스트 스레드(읽기, awaitUntil 폴링)가
	// 동시에 이 목록에 접근한다 — CopyOnWriteArrayList로 그 경합을 안전하게 만든다.
	private final List<Object> sentEvents = new CopyOnWriteArrayList<>();

	private boolean throwIoExceptionOnSend = false;
	private boolean completeWithErrorCalled = false;
	private volatile long sendDelayMillis = 0;

	// emitter를 이 핸들러로 초기화한다 — 프레임워크가 실제 요청 처리 시 수행하는 것과 동일한 진입점(package-private initialize)이다.
	public void attachTo(ResponseBodyEmitter emitter) throws IOException {
		emitter.initialize(this);
	}

	public void failOnNextSend() {
		this.throwIoExceptionOnSend = true;
	}

	// 전송이 지연되는 가짜 구독자를 흉내낸다 — 이후의 모든 send() 호출자가 이 시간만큼 블로킹된다(028 tasks.md
	// 4번 항목 "비차단" 증거용). 호출자 스레드(RedisMessageListenerContainer의 리스너 스레드)만 블로킹되고 이
	// emitter를 등록한 감시 스레드는 영향받지 않아야 한다는 것이 그 테스트의 주장이다.
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
