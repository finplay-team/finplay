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
 * 서술을 뺀 응답 전체를 돌려준다 — 조립 자체는 시장별 리더가 하고 이 클래스는 컨텍스트 로드 → 시장 분기 →
 * 위임만 한다. {@code PostSellFeedbackService}가 여기서 받은 값에 서술만 얹는다.
 *
 * <p><b>이 클래스는 트랜잭션을 갖지 않는 오케스트레이터다</b>(spec §FEED-012 결정 5, 이슈 #282). 그래서 이
 * 경로는 <b>무트랜잭션 오케스트레이터가 트랜잭션을 가진 협력자를 부르는 모양을 두 겹으로 반복한다</b> —
 * 바깥은 {@code PostSellFeedbackService}가 LLM 호출(중앙값 2.5초·p95 3.1초, #198 실측)을 트랜잭션 밖에 두려고
 * 이 리더를 따로 두는 것이고({@code PriceMoveCardWriter}를 저장 쪽에서 뺀 것과 같은 판단이며 방향만 반대다),
 * 안쪽은 이 클래스가 코인 경로의 빗썸 REST 호출(타임아웃 예산 connect 2초·read 3초)을 트랜잭션 밖에 두려고
 * 아래 협력자들을 따로 두는 것이다. 두 겹 모두 <b>느린 외부 호출이 도는 동안 DB 커넥션(풀 20)을 쥐지 않기
 * 위해서다.</b> 이 엔드포인트는 spec 012에서 <b>조회 경로에 LLM이 들어오는 첫 자리</b>이기도 하다
 * (FEED-007 — {@code docs/conventions.md}의 "GET은 부수효과 없음"에 대한 유일한 예외).
 *
 * <p><b>빈을 나누는 것 말고 경계를 좁힐 방법이 없다.</b> 트랜잭션은 메서드 단위라 읽기와 외부 호출이 한
 * 메서드에 있으면 나눌 수 없고, <b>같은 클래스의 private 메서드에 애노테이션을 붙이는 것은 자기호출이라
 * 프록시를 타지 않아 무효다.</b> 더해서 <b>오케스트레이터 쪽에 애노테이션이 남아 있으면</b> 아래에서 부르는
 * 다른 빈의 메서드가 — 그 메서드에 {@code @Transactional}이 없어도 — 전파 기본값 {@code REQUIRED}로 그
 * 트랜잭션에 합류한다. 이슈 #282의 버그가 정확히 그 경로였다.
 *
 * <p><b>협력자 셋이 트랜잭션을 나눠 갖는다.</b> {@link PostSellFeedbackContextReader}가 검증과 원장 컨텍스트를
 * 한 읽기 트랜잭션에서 읽고 lazy 연관을 채워 돌려준다 — <b>존재(404) → 소유(403) → 매수 체결(400)의 검증
 * 순서도 그 클래스가 소유한다.</b> 조립은 {@link StockPostSellFeedbackReader}(주식, 단일 읽기 트랜잭션)와
 * {@link CryptoPostSellFeedbackReader}(코인, 자신도 무트랜잭션 오케스트레이터라 REST 구간 밖에서만 DB를
 * 읽는다)가 시장별로 나눠 맡는다. <b>코인 체결은 400이 아니라 200이다</b>(3차, 이슈 #275).
 *
 * <p><b>수치는 원장에서 그대로 읽는다.</b> {@code buyPrice}는 FIFO 배분 가중평균 매수단가,
 * {@code sellPrice}·{@code quantity}·{@code fee}·{@code realizedPnl}은 {@code trades} 행 그대로다. 재계산하거나
 * LLM에게 계산시키지 않는다(PRD C-004). 배분·lot은 {@code portfolio} 소유라 서비스를 경유한다(§C-6).
 *
 * <p><b>엔티티가 밖으로 나가지 않고 원장에 쓰지도 않는다.</b> 응답 record에 담기는 것은 전부 스칼라·record이고
 * ({@code docs/conventions.md}), 이 spec이 회원별로 쓰는 유일한 테이블({@code trade_feedbacks})은
 * {@code TradeFeedbackWriter}만 건드린다.
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
