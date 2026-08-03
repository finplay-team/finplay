// 매도 체결 1건에 대한 회고 서술을 영속하는 엔티티 — 이 spec에서 유일하게 회원별로 생기는 산출물이다.
package com.finplay.api.feedback.domain;

import com.finplay.api.order.domain.Trade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카드·요약·브리핑은 전 회원이 공유하지만 이 서술만 체결 1건당 1행이라 유니크가 {@code (trade_id)}다
 * (§데이터 모델). 최초 조회 시 생성해 저장하고 이후에는 재사용한다 (FEED-007).
 *
 * <p><b>{@code trade}는 읽기만 하는 참조다.</b> {@code trades}는 {@code order} 소유의 원장이고 이 spec의 어떤
 * 코드도 원장을 바꾸지 않는다 — 그래서 연관에 cascade를 걸지 않았다.
 *
 * <p>{@code narrativeFinalized}는 <b>§C-5의 재생성 게이트를 통과한 서술인지</b>이며 "장 마감이 지났는지"가
 * 아니다 — 집단 비교가 확정(`NO_EVENT`·`INSUFFICIENT_SAMPLE` 포함)돼야 {@code true}가 된다.
 * {@code regenerationAttempts}는 <b>체결 1건당 누적</b> 재시도 횟수이며 날짜로 리셋하지 않는다 — 재생성이
 * 실패하면 {@code generatedAt}을 갱신하지 않아 날짜 기준 자체가 성립하지 않는다 (§데이터 모델·§C-8).
 *
 * <p>재생성 판정과 그에 따른 상태 변경은 조회 서비스 소유다. 여기서 리셋·증가 로직을 만들지 않는다.
 */
@Entity
@Table(name = "trade_feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeFeedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "trade_id", nullable = false)
	private Trade trade;

	@Column(columnDefinition = "TEXT")
	private String narrative;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "narrative_finalized", nullable = false)
	private boolean narrativeFinalized;

	@Column(name = "regeneration_attempts", nullable = false)
	private int regenerationAttempts;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private TradeFeedback(
		Trade trade,
		String narrative,
		NarrativeSource narrativeSource,
		boolean narrativeFinalized,
		int regenerationAttempts,
		LocalDateTime generatedAt) {
		this.trade = trade;
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.narrativeFinalized = narrativeFinalized;
		this.regenerationAttempts = regenerationAttempts;
		this.generatedAt = generatedAt;
	}

	/**
	 * 최초 생성이라 {@code narrativeFinalized = false}·{@code regenerationAttempts = 0}이다 (§C-8의 기본값).
	 * 재생성 게이트를 통과한 서술로 바꾸는 것은 조회 서비스 책임이다.
	 *
	 * @param narrativeSource 매도 회고는 템플릿이 있어 {@code LLM} 아니면 {@code TEMPLATE}이다. {@code NONE}이
	 *                        되지 않으므로 {@code narrative}가 비지 않는다 (§C-4)
	 */
	public static TradeFeedback create(
		Trade trade, String narrative, NarrativeSource narrativeSource, LocalDateTime generatedAt) {
		return new TradeFeedback(trade, narrative, narrativeSource, false, 0, generatedAt);
	}
}
