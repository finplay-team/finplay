// market.stock.* 설정값(수집 락 TTL)을 바인딩하는 프로퍼티 record — StockCollectionLock이 사용한다.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/specs/035-stock-collector-reliability/plan.md §락 설계 세부(TTL 근거)다. market.crypto와 같은
// 방침 — yml과 @DefaultValue 양쪽에 값을 두고 드리프트 테스트로 대조한다.
@ConfigurationProperties(prefix = "market.stock")
public record MarketStockProperties(
	// 다중 인스턴스 중복 수집 방지 락의 TTL(초). 정상 실행은 수십 초~2~3분대로 추정되고, 600초는 프로세스가
	// 죽어 finally를 못 도는 최후의 경우에도 하루 종일 락이 잠기지 않게 하는 여유 마진이다(COLLECT-STAB-001).
	@DefaultValue("600")
	int collectLockTtlSeconds,
	// 정규 08:10 배치 이후 당일 재시도 크론(COLLECT-STAB-003). 08:15~10:45 구간 15분 간격 9회 — 근거는
	// plan.md §재시도 스케줄 근거.
	@DefaultValue("0 15,30,45 8-10 * * MON-FRI")
	String retryCron) {
}
