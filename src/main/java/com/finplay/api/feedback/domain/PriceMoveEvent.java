// 가격이 크게 움직인 구간 1건과 그 서술을 영속하는 엔티티 — 주식 카드와 코인 카드가 한 테이블을 공유한다.
package com.finplay.api.feedback.domain;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <b>주식과 코인이 서로 다른 컬럼을 채운다</b> (§C-9). 주식은 원본 거래일 시간축의 {@code windowStart}·
 * {@code windowEnd}(TIME)를 채우고 {@code occurredAt}이 {@code null}, 코인은 절대 시각 {@code occurredAt}
 * 하나만 채우고 두 TIME이 {@code null}이다. 코인을 TIME으로 두면 00:03 탐지의 구간이 {@code 23:58 ~ 00:03}이라
 * 시작이 끝보다 늦어 어느 날짜를 붙여도 한쪽이 24시간 어긋난다. 두 형태를 한 테이블이 담으므로 해당 컬럼은
 * nullable이며, 형태를 섞지 않도록 <b>정적 팩토리를 시장별로 나눴다</b>.
 *
 * <p>{@code market}은 <b>{@code market/domain/Market}</b>이다 (§C-8). {@code account/domain/Market}과 값 이름이
 * 같아 저장 문자열은 동일하지만, 이 컬럼은 계좌가 아니라 종목·시장 축을 가리킨다.
 *
 * <p>탐지·노출 게이트 판정을 이 엔티티에 두지 않는다 — 탐지는 분봉과 직전 종가만 받는 순수 계산이고(§C-6),
 * 게이트는 조회 시점의 시계를 본다(§C-5). 여기는 확정된 결과를 담기만 한다.
 */
@Entity
@Table(name = "price_move_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMoveEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 20)
	private PriceMoveEventType eventType;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Column(name = "window_start")
	private LocalTime windowStart;

	@Column(name = "window_end")
	private LocalTime windowEnd;

	@Column(name = "occurred_at")
	private LocalDateTime occurredAt;

	@Column(name = "change_rate", nullable = false, precision = 10, scale = 6)
	private BigDecimal changeRate;

	@Column(name = "detection_score", nullable = false, precision = 10, scale = 4)
	private BigDecimal detectionScore;

	@Column(columnDefinition = "TEXT")
	private String narrative;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "reveal_time")
	private LocalTime revealTime;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PriceMoveEvent(
		Instrument instrument,
		Market market,
		PriceMoveEventType eventType,
		LocalDate originTradeDate,
		LocalTime windowStart,
		LocalTime windowEnd,
		LocalDateTime occurredAt,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalTime revealTime,
		LocalDateTime createdAt) {
		this.instrument = instrument;
		this.market = market;
		this.eventType = eventType;
		this.originTradeDate = originTradeDate;
		this.windowStart = windowStart;
		this.windowEnd = windowEnd;
		this.occurredAt = occurredAt;
		this.changeRate = changeRate;
		this.detectionScore = detectionScore;
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.revealTime = revealTime;
		this.createdAt = createdAt;
	}

	/**
	 * 주식 카드를 만든다 — 시각은 <b>원본 거래일 시간축</b>의 TIME이고 {@code occurredAt}은 채우지 않는다.
	 *
	 * @param instrument 주식 종목이어야 한다. {@code market} 컬럼은 이 종목의 시장을 비정규화한 사본이라
	 *                   어긋나면 어떤 제약도 잡지 못한다
	 * @param revealTime 이 카드를 노출할 서비스 날짜의 시각 (§노출 판정). 부여는 배치 책임이며 여기서 계산하지 않는다
	 */
	public static PriceMoveEvent createStock(
		Instrument instrument,
		PriceMoveEventType eventType,
		LocalDate originTradeDate,
		LocalTime windowStart,
		LocalTime windowEnd,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalTime revealTime,
		LocalDateTime now) {
		requireMarket(instrument, Market.STOCK);
		return new PriceMoveEvent(
			instrument,
			Market.STOCK,
			eventType,
			originTradeDate,
			windowStart,
			windowEnd,
			null,
			changeRate,
			detectionScore,
			narrative,
			narrativeSource,
			revealTime,
			now);
	}

	/**
	 * 코인 카드를 만든다 — 실시간이라 {@code revealTime}이 없고(스포일러가 성립하지 않는다) 시각은
	 * {@code occurredAt} 하나뿐이다. {@code windowStart}는 응답에서 계산한다 (§C-9).
	 *
	 * <p><b>{@code originTradeDate}를 받지 않고 {@code occurredAt}에서 파생한다.</b> §C-9가 두 값의 관계를
	 * ({@code originTradeDate = occurredAt}의 KST 날짜)로 고정했는데, 따로 받으면 어긋난 행을 아무도 못 잡는다 —
	 * 코인은 {@code window_start}가 {@code NULL}이라 유니크가 걸리지 않고 {@code validate}도 값은 보지 않는다.
	 * 이 컬럼은 코인의 <b>일일 상한을 세는 기준</b>이라 어긋나면 자정 직후 카드가 전날 몫으로 세어진다.
	 *
	 * @param instrument 코인 종목이어야 한다
	 * @param occurredAt 탐지 시각(= {@code windowEnd})이며 <b>KST 시간축</b>이다. 이 값의 날짜가 그대로
	 *                   {@code originTradeDate}가 된다
	 */
	public static PriceMoveEvent createCrypto(
		Instrument instrument,
		LocalDateTime occurredAt,
		BigDecimal changeRate,
		BigDecimal detectionScore,
		String narrative,
		NarrativeSource narrativeSource,
		LocalDateTime now) {
		requireMarket(instrument, Market.CRYPTO);
		return new PriceMoveEvent(
			instrument,
			Market.CRYPTO,
			PriceMoveEventType.INTRADAY,
			occurredAt.toLocalDate(),
			null,
			null,
			occurredAt,
			changeRate,
			detectionScore,
			narrative,
			narrativeSource,
			null,
			now);
	}

	// market은 instrument.market의 비정규화 사본이라 둘이 어긋나도 FK·유니크·validate 어디에도 걸리지 않는다.
	// 코인 종목으로 만든 주식 형태 카드는 노출 게이트(주식만 reveal_time을 본다)와 조회 범위(코인만 최근
	// 24시간이다)가 서로 다른 시장을 가리키게 만들어, 예외 없이 화면에서 사라진다. 여기서 막는 이유다.
	private static void requireMarket(Instrument instrument, Market expected) {
		if (instrument.getMarket() != expected) {
			throw new IllegalArgumentException(
				"종목의 시장(" + instrument.getMarket() + ")이 카드 형태(" + expected + ")와 다릅니다.");
		}
	}
}
