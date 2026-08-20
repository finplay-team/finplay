// order.limit-fill-executor.* 설정값(체결 비동기 실행기의 킬 스위치·파티션 수·파티션별 대기열 상한·배치 크기)을 바인딩하는 프로퍼티 record — LimitOrderFillExecutorRouter·LimitOrderTriggerListener가 사용한다.
package com.finplay.api.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// enabled·partitionCount·queueCapacityPerPartition의 정본은 ai/adr/0024-limit-order-fill-executor.md이고,
// batchSize의 정본은 ai/adr/0025-limit-order-fill-batch-commit.md다. feedback.query-cache와 같은 방침으로
// yml과 @DefaultValue 양쪽에 값을 둔다 — 설정 없이도 기동하는 것을 보장하는 것은 @DefaultValue이고, 운영 중
// 값을 바꿀 때는 항상 이기는 yml만 고친다.
@ConfigurationProperties(prefix = "order.limit-fill-executor")
public record LimitOrderFillExecutorProperties(
	// 운영 킬 스위치. false면 LimitOrderTriggerListener가 실행기를 아예 거치지 않고 기존처럼 피드 스레드에서
	// 후보를 순차 동기 처리한다 — 비동기 경로에서 예상치 못한 문제가 나오면 재배포 없이 즉시 되돌릴 수단이다
	// (ADR-0015의 query-cache.enabled와 같은 목적).
	@DefaultValue("true")
	boolean enabled,
	// 종목별 체결을 직렬화하는 단일 스레드 실행기(파티션) 개수. 같은 종목의 후보는 항상 같은 파티션으로만
	// 라우팅되므로(instrumentId 해시) 종목 내부 순서(requestedAt asc, id asc)는 파티션 개수와 무관하게
	// 보존되고, 파티션 개수는 오직 서로 다른 종목 간 병렬도만 결정한다.
	@DefaultValue("8")
	int partitionCount,
	// 파티션 하나(단일 스레드)가 처리 대기할 수 있는 체결 작업(청크) 수 상한. 대기열이 가득 차면 새 작업은
	// 버려진다(ADR-0024 §결정 2 — 버려도 주문은 여전히 PENDING이라 다음 가격 틱이 다시 후보로 집어낸다).
	@DefaultValue("200")
	int queueCapacityPerPartition,
	// 파티션 워커가 체결 후보를 몇 건씩 묶어 트랜잭션 1개로 처리할지(ADR-0025). 값이 커질수록 건당 트랜잭션
	// 커밋 오버헤드는 줄지만, 청크 안의 한 건이 실패하면 청크 전체가 롤백되고(나머지는 PENDING으로 남아
	// 다음 가격 틱이 재시도) order→account(→holding) 락을 쥐는 시간도 늘어난다. 이 값이 커지는 만큼
	// queueCapacityPerPartition이 허용하는 실질 주문 건수 상한(queueCapacityPerPartition × batchSize)도
	// 함께 늘어난다.
	@DefaultValue("50")
	int batchSize) {

	// 조용히 방어를 무력화하는 값만 막는다(FeedbackQueryCacheProperties와 같은 기준).
	public LimitOrderFillExecutorProperties {
		if (partitionCount < 1) {
			// 0·음수 파티션은 라우터가 나눗셈에 쓸 분모를 잃어 기동 시점부터 ArithmeticException으로 이어진다.
			throw new IllegalArgumentException("order.limit-fill-executor.partition-count는 1 이상이어야 합니다.");
		}
		if (queueCapacityPerPartition < 1) {
			// 0은 대기열이 없다는 뜻이라 사실상 매 체결마다 즉시 버려지는 것과 같아 실행기 도입 의미가 없다.
			throw new IllegalArgumentException(
				"order.limit-fill-executor.queue-capacity-per-partition은 1 이상이어야 합니다.");
		}
		if (batchSize < 1) {
			// 0은 청크가 비어 아무 후보도 제출되지 않는다는 뜻이라 배치의 의미가 없다.
			throw new IllegalArgumentException("order.limit-fill-executor.batch-size는 1 이상이어야 합니다.");
		}
	}
}
