// 인증 사용자 본인의 시장별 실현손익 순위 단건 응답 (RANK-002)
package com.finplay.api.domain.ranking.dto.response;

import com.finplay.api.domain.ranking.entity.RankingStatus;

// 단건 응답이라 wrapper 개념이 없어 status를 필드로 직접 포함한다(RANK-002가 market을 그렇게 둔 것과 같은 이유).
// rank가 null일 때 status가 READY면 "매도 이력 없음", REBUILDING이면 "집계 준비 중"이다 — 이 구별이 이슈 #279가
// 요구한 "클라이언트가 판별 가능"의 실체다. 기존 필드는 그대로라 필드 추가만으로 하위 호환이 유지된다.
public record MyRankingResponse(String market, RankingStatus status, Integer rank, String nickname,
	long realizedPnl) {
}
