// 커뮤니티 매매 카드 공유에 쓰이는 매도 체결 요약 — 뉴스·서술·집단 비교는 담지 않는다(TRADESHARE-003).
package com.finplay.api.feedback.dto.response;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;

/**
 * {@link PostSellFeedbackResponse}가 이미 계산한 원장 수치 중 매매 카드에 필요한 것만 옮긴 가벼운 응답이다
 * (spec 046 TRADESHARE-002·003). 재계산하지 않는다(PRD C-004).
 */
public record TradeShareSummaryResponse(
	String symbol,
	String name,
	Market market,
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	BigDecimal quantity,
	Long realizedPnl,
	BigDecimal returnRate) {

	public static TradeShareSummaryResponse from(PostSellFeedbackResponse facts, Market market) {
		return new TradeShareSummaryResponse(
			facts.symbol(),
			facts.name(),
			market,
			facts.buyPrice(),
			facts.sellPrice(),
			facts.quantity(),
			facts.realizedPnl(),
			facts.returnRate());
	}
}
