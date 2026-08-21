// PriceMoveDetector가 산출한 변동 구간 1건 — 카드로 확정되기 전의 순수 계산 결과를 서비스 간에 전달한다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 엔티티(<code>PriceMoveEvent</code>)가 아니라 DTO인 이유는 <b>탐지 시점에 카드의 나머지 절반이 아직 없기</b>
 * 때문이다. 근거 매칭·서술·{@code reveal_time}은 확정 경로({@code PriceMoveCardService})의 몫이고(§C-6),
 * 탐지는 분봉과 직전 종가만 보는 순수 계산이라 종목·시계·설정을 알지 못한다.
 *
 * <p>시각은 <b>원본 거래일 시간축</b>의 {@code LocalTime}이다 (§C-8의 {@code window_start}·{@code window_end}).
 * 이 형태는 주식 전용이며 코인 카드는 절대 시각 {@code occurred_at} 하나만 쓴다 (§C-9, 이슈 8번 소유).
 *
 * @param windowStart 시가 갭이면 첫 분봉 시각이고 {@code windowEnd}와 같다
 * @param changeRate 단순수익률. 장중은 {@code exp(cum) - 1}, 시가 갭은 {@code gap}이다 (§C-8 {@code DECIMAL(10,6)})
 * @param detectionScore <b>갭 카드도 반드시 채운다</b> — 컬럼이 {@code NOT NULL}이다 (§탐지 알고리즘(주식))
 */
public record PriceMoveDetectionDto(
	PriceMoveEventType eventType,
	LocalTime windowStart,
	LocalTime windowEnd,
	BigDecimal changeRate,
	BigDecimal detectionScore) {
}
