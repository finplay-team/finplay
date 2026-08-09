// 랭킹 응답이 신뢰할 수 있는 집계인지(READY) 재구성 전 유실 상태인지(REBUILDING)를 나타내는 상태값 (이슈 #279)
package com.finplay.api.ranking.domain;

// rank가 null인 응답에서 이 값이 두 가지 의미를 구별해준다 — READY면 "매도 이력 없음"(정상),
// REBUILDING이면 "집계 준비 중"이다. 프론트엔드가 두 가능성을 함께 안내하며 판단을 사용자에게 전가하던
// 상황을 이 필드 하나로 끝내는 것이 이슈 #279의 요구다.
// Jackson 기본 직렬화로 "READY"/"REBUILDING" 문자열이 된다 — 별도 @JsonValue·컨버터가 필요 없다.
public enum RankingStatus {
	// ZSET이 원장을 반영하고 있다. 랭킹이 비어 있다면 실제로 매도 이력이 없는 것이다.
	READY,
	// ZSET이 유실된 상태다. 재구성(RankingRebuildService) 전이며 지금 값은 신뢰할 수 없다.
	REBUILDING
}
