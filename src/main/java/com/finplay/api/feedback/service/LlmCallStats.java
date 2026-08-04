// 배치 1회분의 LLM 호출 횟수·소요 시간을 스레드 단위로 모으는 수집기 (이슈 #198).
package com.finplay.api.feedback.service;

import org.springframework.stereotype.Component;

/**
 * 생성기({@code NarrativeGenerator} 구현)는 싱글턴 빈이라 "이번 배치에서 몇 번 불렀는가"를 그 자체로는 알 수
 * 없다. 배치가 {@link #startScope()}로 스코프를 열고 {@link #finishScope()}로 닫으면 그 사이에 <b>같은
 * 스레드</b>에서 일어난 호출만 모인다.
 *
 * <p><b>전역 누적값의 차가 아니라 스레드 단위로 센다.</b> 스케줄 풀이 작업당 한 스레드라(application.yml
 * {@code spring.task.scheduling.pool.size}) 개장 전 배치와 코인 매시 배치(매시 05분)가 서로 다른 스레드에서
 * 동시에 돈다. 전역 누적값을 배치 시작·종료에 읽어 빼면 <b>배치가 09:05를 넘긴 날에만</b> 코인 배치의 호출이
 * 얹혀 숫자가 부풀고, 그날은 정확히 이 숫자가 가장 중요한 날이다.
 *
 * <p><b>측정이 배치를 멈추지 않는다</b>(이슈 #198 완료 조건). 스코프가 없으면 {@link #record(long)}는 조용히
 * 아무것도 하지 않고, {@link #finishScope()}는 스코프를 열지 않았어도 0 스냅샷을 준다 — 이 클래스는 어떤
 * 경로에서도 예외를 던지지 않는다.
 */
@Component
public class LlmCallStats {

	// 스코프 안에서만 값이 있다. 배치 스레드 하나가 순차로 도는 구간이라 동기화가 필요 없다.
	private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

	/** 이 스레드의 집계를 새로 시작한다. 이전 스코프가 남아 있으면 버린다. */
	void startScope() {
		CURRENT.set(new Counter());
	}

	/** 집계를 돌려주고 스코프를 닫는다. 스코프가 없으면 {@link Snapshot#EMPTY}다. */
	Snapshot finishScope() {
		Counter counter = CURRENT.get();
		CURRENT.remove();
		return counter == null ? Snapshot.EMPTY : new Snapshot(counter.count, counter.totalNanos);
	}

	/**
	 * 호출 1건을 기록한다. 스코프 밖(조회 경로·다른 배치)에서 온 호출은 세지 않는다.
	 *
	 * <p>소요 시간은 호출부가 {@code System.nanoTime()}으로 재서 넘긴다 — 이 저장소는 시각을 {@code Clock}으로
	 * 주입받고 테스트가 그것을 고정 {@code Clock}으로 바꾸므로, {@code Clock}으로 재면 통합 테스트에서 항상
	 * 0이 나오면서 테스트는 통과한다.
	 */
	void record(long elapsedNanos) {
		Counter counter = CURRENT.get();
		if (counter == null) {
			return;
		}
		counter.count++;
		counter.totalNanos += elapsedNanos;
	}

	/** 스코프 1회분의 집계 결과. */
	record Snapshot(long count, long totalNanos) {

		static final Snapshot EMPTY = new Snapshot(0L, 0L);

		long totalMillis() {
			return totalNanos / 1_000_000L;
		}
	}

	private static final class Counter {

		private long count;

		private long totalNanos;
	}
}
