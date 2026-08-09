// 매도 직후 피드백에서 원장 컨텍스트 로드 → 시장 분기 → 조립 위임만 하는 무트랜잭션 오케스트레이터.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 * 서술을 뺀 응답 전체를 조립한다 — {@code PostSellFeedbackService}가 여기서 받은 값에 서술만 얹는다.
 *
 * <p><b>왜 조회 서비스와 따로 있는가.</b> 이 엔드포인트는 spec 012에서 <b>조회 경로에 LLM이 들어오는 첫 자리</b>다
 * (FEED-007 — {@code docs/conventions.md}의 "GET은 부수효과 없음"에 대한 유일한 예외). LLM 호출은 중앙값 2.5초·
 * p95 3.1초이고(#198 실측) <b>그 동안 DB 커넥션을 쥐면 안 된다.</b> 트랜잭션은 메서드 단위라 읽기와 LLM 호출이
 * 한 메서드에 있으면 경계를 좁힐 방법이 없고, <b>같은 클래스의 private 메서드에 애노테이션을 붙이는 것은
 * 자기호출이라 프록시를 타지 않아 무효다.</b> 그래서 읽기를 별도 빈으로 뺐다 — {@code PriceMoveCardWriter}를
 * 저장 쪽에서 뺀 것과 같은 판단이고 방향만 반대다.
 *
 * <p><b>lazy 연관을 이 트랜잭션 안에서 전부 값으로 바꿔 돌려준다.</b> {@code spring.jpa.open-in-view=false}이므로
 * {@code Trade.instrument}·{@code Trade.stockReplaySession}은 이 메서드가 끝나면 접근할 수 없다 — 응답 record에
 * 담기는 것은 전부 스칼라·record이고 엔티티가 밖으로 나가지 않는다({@code docs/conventions.md}).
 *
 * <p><b>검증 순서를 바꾸지 않는다.</b> {@code TradeService.getOwnedTrade}가 이미 정한 존재(404 {@code NOT_FOUND})
 * → 소유(403 {@code FORBIDDEN})를 그대로 타고, 그 뒤에 매수 체결 → 400이다. 매도 회고 투자일기
 * ({@code JournalService})가 같은 순서를 쓰고 있다. <b>이 검증이 서술 생성보다 먼저 일어나야 한다</b> — 뒤로
 * 미루면 남의 체결로도 LLM이 한 번 불린 뒤에 400이 나간다.
 *
 * <p><b>이 클래스가 두 시장의 진입점이고 조립은 주식만 한다</b>(3차, 이슈 #275). 코인 체결은 검증과 배분 조회를
 * 마친 뒤 {@link CryptoPostSellFeedbackReader}에 넘긴다 — <b>검증과 트랜잭션 경계를 한 곳에 두기 위해서다.</b>
 * 시장 판정을 서비스로 올리면 조립 전에 체결을 한 번 더 읽어야 하고, 코인 쪽에 {@code @Transactional}을 새로
 * 열면 같은 조회가 두 트랜잭션에 걸친다. <b>아래 주식 경로는 3차에서 동작이 바뀌지 않았다</b> — 산술을
 * {@link PostSellArithmetic}으로 옮긴 것은 코인과 식을 공유하기 위한 이동이고 값은 그대로다.
 *
 * <p><b>알려진 한계 — 코인 분기는 이 읽기 트랜잭션 안에서 외부 REST를 부른다</b>(이슈 #282, PR #281 리뷰).
 * 위 문단이 "LLM 호출을 이 트랜잭션 안에 넣지 않기 위해 빈을 나눴다"고 적은 것과 <b>정반대 방향</b>이다 —
 * 코인 경로는 {@code findHoldExtremes}·{@code sellDayClose}·{@code highestCloseAfterSell}·
 * {@code scenarioAtFirstMoveAfterBuy}에서 빗썸을 최대 4회 부르고, 타임아웃 예산이 <b>connect 2초 / read 3초</b>라
 * 최악의 경우 요청 하나가 십수 초 동안 커넥션 1개를 쥔다(풀 20). 지금 고치지 않는 이유는 경계를 나누려면
 * 캔들 조회를 트랜잭션 밖으로 끌어내는 <b>조립 순서 변경</b>이 필요해 이슈 #275 범위를 넘기 때문이다.
 *
 * <p><b>수치는 원장에서 그대로 읽는다.</b> {@code buyPrice}는 FIFO 배분 가중평균 매수단가,
 * {@code sellPrice}·{@code quantity}·{@code fee}·{@code realizedPnl}은 {@code trades} 행 그대로다. 재계산하거나
 * LLM에게 계산시키지 않는다(PRD C-004). 배분·lot은 {@code portfolio} 소유라 서비스를 경유한다(§C-6).
 *
 * <p><b>원장에 쓰지 않는다.</b> 이 클래스는 읽기 전용이고, 이 spec이 회원별로 쓰는 유일한 테이블
 * ({@code trade_feedbacks})은 {@code TradeFeedbackWriter}만 건드린다.
 */
@Component
@RequiredArgsConstructor
class PostSellFeedbackReader {

	private final PostSellFeedbackContextReader postSellFeedbackContextReader;

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader;

	private final CryptoPostSellFeedbackReader cryptoPostSellFeedbackReader;

	/**
	 * 본인 매도 체결 1건의 회고에서 <b>서술을 뺀 전부</b>를 읽는다.
	 *
	 * <p><b>이 메서드에 {@code @Transactional}이 없는 것이 결정이다</b>(spec §FEED-012 결정 5, 이슈 #282). 여기에
	 * 애노테이션이 남아 있으면 아래에서 부르는 <b>다른 빈</b>의 메서드가 — 자기호출이 아니어도, 그 메서드에
	 * {@code @Transactional}이 없어도 — 전파 기본값 {@code REQUIRED}로 이 트랜잭션에 합류해 코인 경로의 빗썸 REST
	 * 호출이 다시 트랜잭션 안에 갇힌다. 트랜잭션 경계는 {@link PostSellFeedbackContextReader}와 두 조립 리더가
	 * 각자 갖는다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수 체결은 400
	 *     {@code VALIDATION_ERROR}다. <b>코인 체결은 400이 아니라 200이다</b>(3차, 이슈 #275)
	 * @return {@code narrative}·{@code narrativeSource}·{@code narrativeStatus} 셋만 {@code null}인 응답.
	 *     그 셋은 {@code PostSellFeedbackService}가 {@code withNarrative}로 얹는다
	 */
	PostSellFeedbackResponse read(Long userId, Long tradeId) {
		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(userId, tradeId);
		Trade trade = context.trade();
		SellAllocationSummaryDto allocation = context.allocation();

		return trade.getInstrument().getMarket() == Market.CRYPTO
			? cryptoPostSellFeedbackReader.read(trade, allocation)
			: stockPostSellFeedbackReader.read(trade, allocation);
	}

}
