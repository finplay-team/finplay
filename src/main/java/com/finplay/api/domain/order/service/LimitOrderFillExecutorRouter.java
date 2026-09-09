package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LimitOrderFillExecutorRouter implements InitializingBean, DisposableBean {

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

	public void submit(Long instrumentId, Runnable task) {
		int index = (int)Math.floorMod(instrumentId, (long)partitions.length);
		partitions[index].execute(task);
	}

	private void onQueueFull(Runnable task, ThreadPoolExecutor executor) {
		log.warn(
			"지정가 체결 실행기 대기열이 가득 차 체결 작업을 버렸습니다(다음 가격 틱에서 해당 주문이 다시 "
				+ "후보로 조회되어 재시도됩니다). queueCapacity={}",
			properties.queueCapacityPerPartition());
	}
}
