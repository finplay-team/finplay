// LimitOrderFillExecutorRouter의 종목별 파티션 라우팅·백프레셔·비차단 제출을 검증하는 단위 테스트다 (ADR-0024).
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LimitOrderFillExecutorRouterTest {

	private LimitOrderFillExecutorRouter router;

	@AfterEach
	void shutdownRouter() {
		if (router != null) {
			router.destroy();
		}
	}

	// §결정 1: 같은 종목(instrumentId)은 항상 같은 파티션(단일 스레드)으로 라우팅되어 제출 순서 그대로
	// FIFO 처리된다 — requestedAt asc, id asc로 정렬해 순서대로 제출하는 리스너의 전제가 여기서 성립해야 한다.
	@Test
	@DisplayName("같은 instrumentId로 제출한 작업은 제출 순서 그대로 순차 실행된다")
	void tasksForTheSameInstrumentIdRunInSubmissionOrder() throws Exception {
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 4, 50, 50));
		int taskCount = 50;
		ConcurrentLinkedQueue<Integer> executionOrder = new ConcurrentLinkedQueue<>();
		CountDownLatch done = new CountDownLatch(taskCount);

		for (int i = 0; i < taskCount; i++) {
			int index = i;
			router.submit(777L, () -> {
				executionOrder.add(index);
				done.countDown();
			});
		}

		assertThat(done.await(10, TimeUnit.SECONDS)).as("모든 작업이 제한 시간 안에 실행됨").isTrue();
		assertThat(executionOrder).containsExactlyElementsOf(
			java.util.stream.IntStream.range(0, taskCount).boxed().toList());
	}

	// §결정 1: 서로 다른 종목은 파티션이 갈리면 병렬로 처리된다 — 한 종목의 느린 체결이 다른 종목의 체결을
	// 지연시키지 않는다는 이번 실행기 도입의 핵심 목적을 직접 증명한다. partitionCount=2로 두 instrumentId
	// (모드 연산으로 서로 다른 파티션에 떨어지는 값)를 고정한다.
	@Test
	@DisplayName("서로 다른 파티션에 떨어지는 종목의 작업은 서로를 기다리지 않고 병렬로 처리된다")
	void tasksForDifferentPartitionsRunConcurrentlyWithoutBlockingEachOther() throws Exception {
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 2, 50, 50));
		Long slowInstrumentId = 100L; // 100 % 2 == 0
		Long fastInstrumentId = 101L; // 101 % 2 == 1
		CountDownLatch releaseSlowTask = new CountDownLatch(1);
		CountDownLatch slowTaskStarted = new CountDownLatch(1);
		CountDownLatch fastTaskDone = new CountDownLatch(1);

		router.submit(slowInstrumentId, () -> {
			slowTaskStarted.countDown();
			awaitQuietly(releaseSlowTask);
		});
		assertThat(slowTaskStarted.await(5, TimeUnit.SECONDS)).as("느린 종목 작업이 시작됨").isTrue();

		router.submit(fastInstrumentId, fastTaskDone::countDown);

		try {
			assertThat(fastTaskDone.await(2, TimeUnit.SECONDS))
				.as("느린 종목 작업이 아직 안 끝났는데도 다른 파티션의 작업은 완료된다").isTrue();
		} finally {
			releaseSlowTask.countDown();
		}
	}

	// §결정 2: 파티션 대기열이 가득 차면 초과분은 예외 없이 버려진다 — 제출 스레드(피드 스레드)는 절대 막히지
	// 않아야 한다(캐치되지 않는 예외나 대기가 있으면 이번 개선의 목적이 깨진다).
	@Test
	@DisplayName("대기열이 가득 차면 초과 작업은 예외 없이 버려지고 제출 스레드는 즉시 반환된다")
	void submissionsBeyondQueueCapacityAreDiscardedWithoutBlockingTheCaller() throws Exception {
		int queueCapacity = 2;
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 1, queueCapacity, 50));
		Long instrumentId = 42L;
		CountDownLatch releaseWorker = new CountDownLatch(1);
		CountDownLatch workerTaskStarted = new CountDownLatch(1);
		AtomicInteger executedCount = new AtomicInteger();

		// 워커 스레드를 하나 점유해 두어야 뒤이은 제출들이 대기열에 쌓인다(단일 스레드 파티션, corePool=maxPool=1).
		router.submit(instrumentId, () -> {
			workerTaskStarted.countDown();
			awaitQuietly(releaseWorker);
			executedCount.incrementAndGet();
		});
		assertThat(workerTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

		// 대기열을 정확히 채운다.
		for (int i = 0; i < queueCapacity; i++) {
			router.submit(instrumentId, executedCount::incrementAndGet);
		}

		long submitStartedAt = System.nanoTime();
		// 대기열 + 실행 중 1건까지 이미 꽉 찼다 — 이 제출은 거부돼야 한다. 예외를 던지면 이 호출 자체가
		// 테스트를 실패시키므로, 여기서 예외가 안 나는 것 자체가 "제출 스레드가 막히지 않는다"의 증거다.
		router.submit(instrumentId, executedCount::incrementAndGet);
		long submitElapsedMillis = Duration.ofNanos(System.nanoTime() - submitStartedAt).toMillis();
		assertThat(submitElapsedMillis)
			.as("대기열이 가득 차도 제출 자체는 즉시 반환된다(대기·차단 없음)")
			.isLessThan(500L);

		releaseWorker.countDown();
		// 점유 작업 1 + 대기열 채운 것 2 = 3건만 실제로 실행된다. 마지막 초과 제출은 실행되지 않는다.
		awaitUntil(() -> executedCount.get() >= 1 + queueCapacity, Duration.ofSeconds(5),
			"점유 작업과 대기열에 쌓인 작업들이 실행되지 않았다");
		// 초과분이 뒤늦게라도 실행되지는 않는지 잠깐 더 관찰한다(드롭이 아니라 지연 실행이면 여기서 잡힌다).
		Thread.sleep(200);
		assertThat(executedCount.get()).as("초과 제출은 드롭되어 끝까지 실행되지 않는다").isEqualTo(1 + queueCapacity);
	}

	// §결정 1 보조: instrumentId 해시가 partitionCount로 나눠떨어지는 값이어도(음수 해시 포함 가능성) 예외 없이
	// 항상 유효한 파티션 인덱스로 라우팅된다.
	@Test
	@DisplayName("파티션이 1개뿐이면 모든 종목이 그 파티션으로 직렬화된다")
	void singlePartitionSerializesEveryInstrument() throws Exception {
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 1, 100, 50));
		List<Long> instrumentIds = List.of(1L, 2L, 3L, 999_999L);
		CountDownLatch done = new CountDownLatch(instrumentIds.size());

		for (Long instrumentId : instrumentIds) {
			router.submit(instrumentId, done::countDown);
		}

		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
	}

	private static LimitOrderFillExecutorRouter startedRouter(LimitOrderFillExecutorProperties properties) {
		LimitOrderFillExecutorRouter newRouter = new LimitOrderFillExecutorRouter(properties);
		newRouter.afterPropertiesSet();
		return newRouter;
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("래치 대기 중 인터럽트", e);
		}
	}

	// CryptoCardPushFanoutIntegrationTest의 awaitUntil 관례를 그대로 따른다.
	private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout,
		String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
	}
}
