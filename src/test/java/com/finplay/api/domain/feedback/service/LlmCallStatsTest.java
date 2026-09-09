package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

	@Test
	@DisplayName("스코프 밖에서 기록하거나 열지 않고 닫아도 던지지 않고 0을 준다")
	void staysQuietOutsideAnyScope() {
		assertThatCode(() -> llmCallStats.record(7_000_000L)).doesNotThrowAnyException();

		LlmCallStats.Snapshot snapshot = llmCallStats.finishScope();

		assertThat(snapshot.count()).isZero();
		assertThat(snapshot.totalMillis()).isZero();
	}
}
