// 매수 전에 기록한 투자 실습의 수량과 손절·익절 의도를 표현하는 인메모리 불변 값 객체(#193: JPA 엔티티에서 전환)
package com.finplay.api.education.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeIntention(
	Long intentionId,
	Long userId,
	Long instrumentId,
	BigDecimal quantity,
	BigDecimal stopLoss,
	BigDecimal takeProfit,
	LocalDateTime createdAt) {

	public static PracticeIntention create(
		Long intentionId,
		Long userId,
		Long instrumentId,
		BigDecimal quantity,
		BigDecimal stopLoss,
		BigDecimal takeProfit,
		LocalDateTime createdAt) {
		return new PracticeIntention(
			intentionId, userId, instrumentId, quantity, stopLoss, takeProfit, createdAt);
	}
}
