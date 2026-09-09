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

	@Test
	@DisplayName("서로 다른 파티션에 떨어지는 종목의 작업은 서로를 기다리지 않고 병렬로 처리된다")
	void tasksForDifferentPartitionsRunConcurrentlyWithoutBlockingEachOther() throws Exception {
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 2, 50, 50));
		Long slowInstrumentId = 100L;
		Long fastInstrumentId = 101L;
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

	@Test
	@DisplayName("대기열이 가득 차면 초과 작업은 예외 없이 버려지고 제출 스레드는 즉시 반환된다")
	void submissionsBeyondQueueCapacityAreDiscardedWithoutBlockingTheCaller() throws Exception {
		int queueCapacity = 2;
		router = startedRouter(new LimitOrderFillExecutorProperties(true, 1, queueCapacity, 50));
		Long instrumentId = 42L;
		CountDownLatch releaseWorker = new CountDownLatch(1);
		CountDownLatch workerTaskStarted = new CountDownLatch(1);
		AtomicInteger executedCount = new AtomicInteger();

		router.submit(instrumentId, () -> {
			workerTaskStarted.countDown();
			awaitQuietly(releaseWorker);
			executedCount.incrementAndGet();
		});
		assertThat(workerTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

		for (int i = 0; i < queueCapacity; i++) {
			router.submit(instrumentId, executedCount::incrementAndGet);
		}

		long submitStartedAt = System.nanoTime();
		router.submit(instrumentId, executedCount::incrementAndGet);
		long submitElapsedMillis = Duration.ofNanos(System.nanoTime() - submitStartedAt).toMillis();
		assertThat(submitElapsedMillis)
			.as("대기열이 가득 차도 제출 자체는 즉시 반환된다(대기·차단 없음)")
			.isLessThan(500L);

		releaseWorker.countDown();
		awaitUntil(() -> executedCount.get() >= 1 + queueCapacity, Duration.ofSeconds(5),
			"점유 작업과 대기열에 쌓인 작업들이 실행되지 않았다");
		Thread.sleep(200);
		assertThat(executedCount.get()).as("초과 제출은 드롭되어 끝까지 실행되지 않는다").isEqualTo(1 + queueCapacity);
	}

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
