// 종목 변동 원인 카드 목록 응답 — 현재 재생세션의 원본 거래일과 노출 시각이 지난 카드만 담는다.
package com.finplay.api.feedback.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * <b>비어 있는 것이 오류가 아니다</b> (FEED-006·§실패 처리). 카드가 0건이면 {@code moves=[]}이고 200이며,
 * 재생세션이 {@code READY}가 아니면 {@code originTradeDate}까지 {@code null}인 채로 200이다 — 그때는 어떤
 * 거래일을 재생 중인지 자체가 확정되지 않은 상태라 날짜를 지어낼 수 없다.
 *
 * @param originTradeDate 주식은 현재 재생세션의 원본 거래일. <b>코인은 실시간이라 항상 {@code null}</b>이다
 */
public record PriceMoveListResponse(LocalDate originTradeDate, List<PriceMoveItem> moves) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (docs/agent-mistakes.md 2026-07-29).
	public PriceMoveListResponse {
		moves = List.copyOf(moves);
	}

	/** 재생세션 미준비·코인처럼 원본 거래일이 없는 응답. */
	public static PriceMoveListResponse empty() {
		return new PriceMoveListResponse(null, List.of());
	}

	public static PriceMoveListResponse of(LocalDate originTradeDate, List<PriceMoveItem> moves) {
		return new PriceMoveListResponse(originTradeDate, moves);
	}
}
