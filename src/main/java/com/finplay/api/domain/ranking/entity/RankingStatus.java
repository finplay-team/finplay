// 랭킹 응답이 신뢰할 수 있는 집계인지(READY) 재구성 전 유실 상태인지(REBUILDING) 또는 Redis 장애로 아예
// 읽을 수 없는 상태인지(UNAVAILABLE)를 나타내는 상태값 (이슈 #279, UNAVAILABLE은 이슈 #288)
package com.finplay.api.domain.ranking.entity;

// rank가 null인 응답에서 이 값이 의미를 구별해준다 — READY면 "매도 이력 없음"(정상),
// REBUILDING이면 "집계 준비 중", UNAVAILABLE이면 "지금 이 값을 읽을 수 없음"이다. 프론트엔드가 두 가능성을
// 함께 안내하며 판단을 사용자에게 전가하던 상황을 이 필드 하나로 끝내는 것이 이슈 #279의 요구였고,
// UNAVAILABLE은 여기에 "유실"과 다른 "장애" 축을 더한다(이슈 #288, 유실과 장애는 원인·복구 경로가 다르다).
// Jackson 기본 직렬화로 문자열이 된다 — 별도 @JsonValue·컨버터가 필요 없다.
public enum RankingStatus {
	// ZSET이 원장을 반영하고 있다. 랭킹이 비어 있다면 실제로 매도 이력이 없는 것이다.
	READY,
	// ZSET이 유실된 상태다. 재구성(RankingRebuildService) 전이며 지금 값은 신뢰할 수 없다.
	REBUILDING,
	// Redis에 연결할 수 없어 ZSET 자체를 읽지 못했다(BusinessException, ErrorCode.RANKING_STORE_UNAVAILABLE). 유실(REBUILDING)과
	// 달리 재구성으로 해결되지 않는다 — Redis 연결이 복구되면 별도 조치 없이 다음 조회부터 정상화된다.
	UNAVAILABLE
}
