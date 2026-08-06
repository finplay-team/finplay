// feedback.query-cache.* 설정값(요약·브리핑 조회 캐시의 킬 스위치와 락·대기 시간)을 바인딩하는 프로퍼티 record — FeedbackQueryCache가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 값의 정본은 docs/adr/0015-feedback-query-cache.md다. FeedbackCryptoProperties·FeedbackNewsProperties와 같은
// 방침으로 yml과 @DefaultValue 양쪽에 값을 둔다 — 설정 없이도 기동하는 것을 보장하는 것은 @DefaultValue이고,
// 운영 중 값을 바꿀 때는 항상 이기는 yml만 고친다.
//
// 세 숫자는 실측이 아니라 추정이다(ADR-0015 §후속). 조회 원본이 인덱스로 덮인 단일 행·구간 조회라 수 ms라는
// 전제에서 나왔으며, 다중 인스턴스 배포 후 실제 대기 발생률을 보고 조정한다.
@ConfigurationProperties(prefix = "feedback.query-cache")
public record FeedbackQueryCacheProperties(
	// 운영 킬 스위치. false면 FeedbackQueryCache가 Redis를 아예 접촉하지 않고 로더(DB)로 직행한다.
	// 이 저장소의 첫 조회 경로 캐시이고 사용자가 기다리는 읽기 앞단에 새 계층이 들어가는데, 폴백(DB 직행)이
	// 이미 검증된 기존 경로라 재배포 없이 즉시 되돌릴 수단을 둔다.
	@DefaultValue("true")
	boolean enabled,
	// 만료 쏠림 방어 락(RedisLock)의 TTL(밀리초). 원본이 수 ms인 조회 경로 기준이라 CryptoWatchLock의
	// 45초와 값이 다르다(ADR-0015 §4).
	@DefaultValue("1000")
	long lockTtlMillis,
	// 락을 얻지 못했을 때 캐시가 채워지길 기다리는 최대 시간(밀리초). 이 값이 원본 소요보다 짧으면 방어가
	// 무력해진다 — 대기가 전부 타임아웃돼 그대로 DB로 직행하기 때문이다(ADR-0015 §후속).
	@DefaultValue("300")
	long waitMillis,
	// 위 대기 중 캐시를 다시 확인하는 간격(밀리초).
	@DefaultValue("20")
	long pollMillis) {

	// 조용히 방어를 무력화하는 값만 막는다 — 셋 다 예외도 로그도 없이 "캐시는 켜져 있는데 아무 것도 막지 못하는"
	// 상태로 이어진다(FeedbackCryptoProperties와 같은 기준).
	public FeedbackQueryCacheProperties {
		if (lockTtlMillis < 1) {
			// 0·음수는 Duration.ofMillis가 Redis 명령 오류를 유발하고, RedisLock.tryLock의
			// catch(RuntimeException)이 이를 삼켜 락이 영구히 획득 실패가 된다 — 모든 요청이 대기 후
			// fail-open으로 DB에 직행하는데 로그는 DEBUG 한 줄뿐이다(#244의 watch-lock-ttl-seconds와 같은 형태).
			throw new IllegalArgumentException("feedback.query-cache.lock-ttl-millis는 1 이상이어야 합니다.");
		}
		if (waitMillis < 0) {
			// 0은 "대기하지 않고 즉시 DB 직행"이라 유효한 값이다. 음수만 막는다.
			throw new IllegalArgumentException("feedback.query-cache.wait-millis는 0 이상이어야 합니다.");
		}
		if (pollMillis < 1) {
			// 0이면 폴링이 바쁜 대기(busy-wait)가 되어 대기 스레드가 CPU를 태운다.
			throw new IllegalArgumentException("feedback.query-cache.poll-millis는 1 이상이어야 합니다.");
		}
	}
}
