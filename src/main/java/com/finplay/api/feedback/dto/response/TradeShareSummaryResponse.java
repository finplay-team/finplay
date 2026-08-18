// 커뮤니티 매매 카드 공유에 쓰이는 매도 체결 요약 — 뉴스·서술·집단 비교는 담지 않는다(TRADESHARE-003).
package com.finplay.api.feedback.dto.response;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;

/**
 * {@code PostSellFeedbackContextReader.loadContext}가 읽은 원장 값(trade·allocation)만으로 조립하는 가벼운
 * 응답이다(spec 046 TRADESHARE-002·003). <b>{@code PostSellFeedbackReader.read()}를 호출하지 않는다</b> — 그
 * 경로는 가격 변동 카드·뉴스·반사실·집단 비교를 전부 계산하고(코인은 빗썸 REST까지) 그중 8개 필드만 쓰는
 * 무거운 경로다. 조립은 {@code PostSellFeedbackService.getTradeShareSummary}가 한다. 재계산하지 않는다(PRD
 * C-004) — {@code buyPrice}는 {@code SellAllocationSummaryDto}의 FIFO 가중평균, {@code returnRate}는
 * {@code PostSellArithmetic.returnRate}로 매도 직후 피드백과 같은 식을 그대로 재사용한다.
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
}
