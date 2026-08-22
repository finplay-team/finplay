// LlmCallStats가 배치 1회분만 세고 다른 스레드·스코프 밖 호출을 섞지 않는지 검증하는 단위 테스트.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 이 클래스가 지키는 계약은 두 개다. "배치 스레드에서 일어난 호출만 센다"와 "어떤 순서로 불려도 던지지 않는다".
// 앞의 것이 깨지면 개장 전 배치의 호출 수에 코인 매시 배치가 얹히고, 뒤의 것이 깨지면 측정이 배치를 멈춘다.
class LlmCallStatsTest {

	private final LlmCallStats llmCallStats = new LlmCallStats();

	@Test
	@DisplayName("스코프 안에서 기록한 호출만 횟수와 소요 시간에 모인다")
	void collectsOnlyTheCallsRecordedInsideTheScope() {
		llmCallStats.startScope();
		llmCallStats.record(3_000_000L);
		llmCallStats.record(5_000_000L);

		LlmCallStats.Snapshot snapshot = llmCallStats.finishScope();

		assertThat(snapshot.count()).isEqualTo(2);
		assertThat(snapshot.totalMillis()).isEqualTo(8);
	}

	@Test
	@DisplayName("스코프를 닫으면 다음 스코프는 0에서 다시 센다")
	void startsFromZeroOnTheNextScope() {
		llmCallStats.startScope();
		llmCallStats.record(3_000_000L);
		llmCallStats.finishScope();

		llmCallStats.startScope();
		llmCallStats.record(1_000_000L);

		assertThat(llmCallStats.finishScope().count()).isEqualTo(1);
	}

	// 이 단정이 이 클래스가 전역 누적값의 차가 아닌 이유다. 스케줄 풀은 작업마다 스레드가 다르고, 개장 전 배치가
	// 09:05를 넘기면 코인 매시 배치가 같은 시간대에 LLM을 부른다 — 전역으로 세면 그날 숫자만 부풀어 오른다.
	@Test
	@DisplayName("다른 스레드에서 일어난 호출은 이 스코프에 섞이지 않는다")
	void neverMixesCallsRecordedOnAnotherThread() throws InterruptedException {
		llmCallStats.startScope();
		llmCallStats.record(2_000_000L);

		CountDownLatch done = new CountDownLatch(1);
		Thread other = new Thread(() -> {
			llmCallStats.startScope();
			llmCallStats.record(9_000_000L);
			llmCallStats.record(9_000_000L);
			llmCallStats.finishScope();
			done.countDown();
		});
		other.start();
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

		LlmCallStats.Snapshot snapshot = llmCallStats.finishScope();

		assertThat(snapshot.count()).isEqualTo(1);
		assertThat(snapshot.totalMillis()).isEqualTo(2);
	}

	// 측정이 배치를 멈추지 않는다(이슈 #198 완료 조건) — 스코프 밖 호출·스코프 없는 종료가 전부 조용해야 한다.
	@Test
	@DisplayName("스코프 밖에서 기록하거나 열지 않고 닫아도 던지지 않고 0을 준다")
	void staysQuietOutsideAnyScope() {
		assertThatCode(() -> llmCallStats.record(7_000_000L)).doesNotThrowAnyException();

		LlmCallStats.Snapshot snapshot = llmCallStats.finishScope();

		assertThat(snapshot.count()).isZero();
		assertThat(snapshot.totalMillis()).isZero();
	}
}
