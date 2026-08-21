// 가격 틱 처리 스레드가 지정가 체결(fillBatch) 실행을 종목별로 직렬화된 전용 스레드에 위임하는 라우터
package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * {@code LimitOrderTriggerListener}가 후보 조회까지만 피드 스레드에서 하고, 실제 체결
 * ({@code LimitOrderFillService.fillBatch})은 이 라우터가 고르는 실행기에 맡긴 뒤 즉시 반환하게 한다
 * (ADR-0024). 새 인프라를 추가하지 않고 스프링 내장 {@link ThreadPoolTaskExecutor}만으로 구성한다.
 *
 * <p><b>종목별 직렬화(ADR-0024 §결정 1).</b> 같은 종목의 지정가는 "먼저 건 사람이 먼저 체결"이 계약이다
 * ({@code idx_orders_limit_fill}의 {@code requestedAt asc, id asc}). 파티션(코어·최대 풀 크기 1인 단일 스레드
 * 실행기)을 {@code partitionCount}개 두고 {@code instrumentId}를 해시해 항상 같은 파티션으로 라우팅한다 —
 * 같은 종목의 작업은 항상 같은 파티션의 큐에 FIFO로 쌓여 제출 순서 그대로 처리되므로, 리스너가 이미 정렬해
 * 넘기는 순서가 그대로 보존된다. 서로 다른 종목만 파티션이 갈리면 병렬로 처리된다.
 *
 * <p><b>백프레셔(ADR-0024 §결정 2).</b> 파티션마다 대기열 상한을 두고, 상한을 넘으면 새 작업은 버린다(재시도
 * 큐에 쌓지 않는다). {@code fillBatch}의 대상 주문은 버려져도 여전히 DB에 {@code PENDING}으로 남아 있으므로
 * 다음 가격 틱이 같은 후보를 다시 집어낸다 — 유실이 아니라 다음 틱으로의 지연이다. {@code CallerRunsPolicy}로
 * 피드 스레드에서 직접 실행하지 않는 이유도 이것이다 — 그러면 이번 개선이 없애려는 "피드 스레드가 체결이 끝날
 * 때까지 막힌다"가 대기열이 찬 상황에서 그대로 재현된다.
 *
 * <p><b>인스턴스 종료 시 유실(ADR-0024 §결정 3 — 알려진 한계).</b> 대기열에 남은 작업은 JVM 메모리에만 있어
 * 인스턴스가 죽으면(OOM·강제 종료) 그대로 사라진다. 정상 종료(SIGTERM)는 {@code awaitTerminationSeconds}만큼
 * 대기열을 비우려 시도하지만, 이것도 그 시간 안에 못 끝내면 유실을 막지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LimitOrderFillExecutorRouter implements InitializingBean, DisposableBean {

	// 정상 종료(SIGTERM) 시 대기열에 남은 체결 작업을 비울 최대 대기 시간. 블루-그린 배포 전환의 일반적인
	// 헬스체크·드레인 유예 구간 안에서 대기열을 비울 수 있도록 여유를 두되, 무한정 종료를 지연시키지 않는다.
	private static final int AWAIT_TERMINATION_SECONDS = 10;

	private final LimitOrderFillExecutorProperties properties;

	private ThreadPoolTaskExecutor[] partitions;

	@Override
	public void afterPropertiesSet() {
		partitions = new ThreadPoolTaskExecutor[properties.partitionCount()];
		for (int i = 0; i < partitions.length; i++) {
			partitions[i] = createPartition(i);
		}
		log.info(
			"지정가 체결 실행기를 초기화했습니다. partitionCount={}, queueCapacityPerPartition={}",
			properties.partitionCount(), properties.queueCapacityPerPartition());
	}

	private ThreadPoolTaskExecutor createPartition(int index) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(properties.queueCapacityPerPartition());
		executor.setThreadNamePrefix("limit-order-fill-" + index + "-");
		executor.setRejectedExecutionHandler(this::onQueueFull);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
		executor.initialize();
		return executor;
	}

	@Override
	public void destroy() {
		for (ThreadPoolTaskExecutor executor : partitions) {
			executor.shutdown();
		}
	}

	/**
	 * {@code instrumentId}가 항상 같은 파티션으로 라우팅되도록 위임한다. 대기열이 가득 차면
	 * {@link #onQueueFull}이 조용히 처리하므로 이 메서드는 예외를 던지지 않는다 — 호출부(피드 스레드)는
	 * 언제나 즉시 반환된다.
	 */
	public void submit(Long instrumentId, Runnable task) {
		int index = (int)Math.floorMod(instrumentId, (long)partitions.length);
		partitions[index].execute(task);
	}

	// 대기열이 가득 찼을 때 호출된다. 여기서 대기하거나 피드 스레드에서 직접 실행(CallerRunsPolicy)하면
	// 이번 개선의 목적(피드 스레드는 항상 즉시 반환)이 깨지므로 로그만 남기고 버린다 — 클래스 상단 문서의
	// 근거 참고.
	private void onQueueFull(Runnable task, ThreadPoolExecutor executor) {
		log.warn(
			"지정가 체결 실행기 대기열이 가득 차 체결 작업을 버렸습니다(다음 가격 틱에서 해당 주문이 다시 "
				+ "후보로 조회되어 재시도됩니다). queueCapacity={}",
			properties.queueCapacityPerPartition());
	}
}
