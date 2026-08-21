// 종목 변동 원인 카드 목록 응답 — 현재 재생세션의 원본 거래일과 노출 시각이 지난 카드만 담는다.
package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>비어 있는 것이 오류가 아니다</b> (FEED-006·§실패 처리). 카드가 0건이면 {@code moves=[]}이고 200이며,
 * 재생세션이 {@code READY}가 아니면 {@code originTradeDate}까지 {@code null}인 채로 200이다 — 그때는 어떤
 * 거래일을 재생 중인지 자체가 확정되지 않은 상태라 날짜를 지어낼 수 없다.
 *
 * <p><b>{@code status}가 그 두 상황을 응답만으로 갈라준다</b> (Issue #280). 이 필드가 없을 때는 주식의
 * 재생세션 미준비와 코인의 카드 0건이 똑같이 {@code {"originTradeDate":null,"moves":[]}}였고(이 DTO에는
 * {@code @JsonInclude(NON_NULL)}이 없어 {@code null}도 키가 남는다 — 값까지 같았다), 호출부가
 * 종목의 {@code market}을 따로 알고 있어야만 안내 문구를 갈랐다. 형제 세 경로(Part C {@code summaryStatus},
 * Part D {@code status}, 매도 회고)는 모두 상태 필드로 자기 완결적이며 이 경로만 예외였다.
 *
 * @param originTradeDate 주식은 현재 재생세션의 원본 거래일. <b>코인은 실시간이라 항상 {@code null}</b>이다
 * @param status {@code READY}(카드 1건 이상)·{@code EMPTY}(카드 0건)·{@code NOT_YET}(주식 재생세션 미준비).
 *     <b>{@code UNAVAILABLE}은 나오지 않는다</b> — 카드 서술은 LLM이 실패해도 템플릿으로 대체되므로
 *     ({@code narrative_source}가 {@code LLM} 또는 {@code TEMPLATE}, §C-4) 서술 없는 카드가 존재하지 않는다
 */
public record PriceMoveListResponse(LocalDate originTradeDate, FeedbackContentStatus status,
	List<PriceMoveListItemResponse> moves) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29).
	public PriceMoveListResponse {
		moves = List.copyOf(moves);
	}

	/**
	 * 주식 재생세션이 {@code READY}가 아닌 응답 — 어떤 거래일을 재생 중인지 자체가 미확정이라
	 * {@code originTradeDate}가 {@code null}이다.
	 *
	 * <p><b>코인은 이 팩터리를 쓰지 않는다.</b> 값이 같아 보여도 코인의 {@code originTradeDate=null}은
	 * "아직"이 아니라 개념 부재라서 {@code EMPTY}이며, 그 구분이 이 필드를 넣은 이유다.
	 */
	public static PriceMoveListResponse notYet() {
		return new PriceMoveListResponse(null, FeedbackContentStatus.NOT_YET, List.of());
	}

	/** {@code status}는 카드 유무로 정한다 — 호출부가 따로 넘기지 않아야 목록과 상태가 어긋날 수 없다. */
	public static PriceMoveListResponse of(LocalDate originTradeDate, List<PriceMoveListItemResponse> moves) {
		FeedbackContentStatus status = moves.isEmpty() ? FeedbackContentStatus.EMPTY : FeedbackContentStatus.READY;
		return new PriceMoveListResponse(originTradeDate, status, moves);
	}
}
