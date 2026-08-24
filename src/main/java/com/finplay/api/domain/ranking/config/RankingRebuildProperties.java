// ranking.rebuild.lock-ttl-seconds를 바인딩하는 프로퍼티 record — RankingRebuildLock이 사용한다.
package com.finplay.api.domain.ranking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// ranking.rebuild.cron은 이 record에 두지 않는다 — @Scheduled(cron = "${ranking.rebuild.cron}")가 Environment에서
// 직접 읽고, 크론값은 이 record가 다루는 락 TTL과 무관하다(application.yml 해당 블록 주석 "record를 따로 두지
// 않는 이유" 참고). lock-ttl-seconds는 RankingRebuildLock이 Duration으로 변환해 써야 하는 실제 Java 값이라 record가
// 필요해진 경우다.
@ConfigurationProperties(prefix = "ranking.rebuild")
public record RankingRebuildProperties(
	// 시장 단위 재구성 중복 실행 방지 락의 TTL(초, 이슈 #539). market.stock.collect-lock-ttl-seconds
	// (COLLECT-STAB-001)와 같은 논리 — 정상 실행은 수 초~수십 초로 추정되고, 600초는 프로세스가 죽어 finally를
	// 못 도는 최후의 경우에도 다음 재구성 기회(기동 또는 익일 04:20) 전까지만 잠기게 하는 안전 마진이다.
	@DefaultValue("600")
	int lockTtlSeconds) {
}
