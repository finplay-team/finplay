// 실습 3단계 각 단계의 잠금 상태와 무관하게 항상 채워지는 evidence 객체(필드는 조건 미충족 시 null)를 담는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeEvidenceResponse(
	Long favoriteId,
	LocalDateTime favoriteCreatedAt,
	Long intentionId,
	LocalDateTime intentionCreatedAt,
	Long buyTradeId,
	LocalDateTime buyTradeExecutedAt,
	Long holdingId,
	BigDecimal referenceStopLossPrice,
	BigDecimal referenceTakeProfitPrice,
	Long observationId,
	LocalDateTime observationObservedAt,
	String evidenceType,
	Long reflectionId,
	LocalDateTime reflectionCreatedAt,
	Long sellTradeId,
	LocalDateTime sellTradeExecutedAt,
	LocalDateTime saleDeadlineAt) {

	/** 잠긴 단계·아무 증거도 없는 단계에 쓰는 모두 null인 evidence 객체(spec MKT-PRACTICE-008). */
	public static PracticeEvidenceResponse empty() {
		return new PracticeEvidenceResponse(
			null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	/** 1단계(즐겨찾기)만 완료됐을 때 쓰는, favorite 쌍만 채워진 evidence 객체. */
	public static PracticeEvidenceResponse favoriteOnly(Long favoriteId, LocalDateTime favoriteCreatedAt) {
		return new PracticeEvidenceResponse(
			favoriteId, favoriteCreatedAt, null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null);
	}
}
