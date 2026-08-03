// 시장·거래일 단위의 개장 전 브리핑 1건을 영속하는 엔티티 — 전 회원이 같은 행을 공유한다.
package com.finplay.api.feedback.domain;

import com.finplay.api.market.domain.Market;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유니크는 {@code (market, origin_trade_date)}이며 주식은 개장 전 배치가 하루 1건, 코인은 코인 배치가 같은 행을
 * UPSERT 한다 (§데이터 모델). {@code market}은 <b>{@code market/domain/Market}</b>이다 — 계좌가 아니라 시장
 * 축이다 (§C-8).
 *
 * <p>{@code originTradeDate}를 코인 행도 채우는 이유, {@code summary}가 {@code null}일 수 있는 이유,
 * {@code items}를 저장하지 않는 이유는 {@code InstrumentNewsSummary}와 같다. 특히 브리핑은 저장된 행만으로
 * {@code EMPTY}와 {@code UNAVAILABLE}이 구분되지 않아(둘 다 {@code summary=null}) <b>조회 시 {@code items}
 * 개수와 행 존재 여부로 판정한다</b> (§C-4).
 */
@Entity
@Table(name = "market_briefings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketBriefing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private MarketBriefing(
		Market market,
		LocalDate originTradeDate,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		this.market = market;
		this.originTradeDate = originTradeDate;
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}

	/**
	 * @param originTradeDate 주식은 원본 거래일, 코인은 배치 실행 시점의 KST 날짜다. 코인도 비우지 않는다 (§C-9)
	 * @param summary         후검증 재생성 후에도 걸렸으면 {@code null}이고, 그때 {@code narrativeSource}가
	 *                        {@code NONE}이다 (§C-4)
	 */
	public static MarketBriefing create(
		Market market,
		LocalDate originTradeDate,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		return new MarketBriefing(market, originTradeDate, summary, narrativeSource, generatedAt);
	}
}
