// 종목·거래일·범위 단위의 뉴스 요약 1건을 영속하는 엔티티 — 전 회원이 같은 행을 공유한다.
package com.finplay.api.feedback.domain;

import com.finplay.api.market.domain.Instrument;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주식은 {@code PRE_MARKET}·{@code FULL} 2건, 코인은 {@code ROLLING_24H} 1건이며 유니크는
 * {@code (instrument_id, origin_trade_date, scope)}다 (§데이터 모델).
 *
 * <p><b>{@code originTradeDate}는 코인 행도 채운다</b> (§C-9). 컬럼이 유니크 키의 일부인데 MySQL 유니크는
 * {@code NULL}을 중복으로 보지 않아, 비워 두면 코인 배치의 UPSERT가 갱신이 아니라 새 행을 매시 쌓는다. 코인 행의
 * 값은 배치 실행 시점의 KST 날짜다 — 이 행에는 {@code occurred_at}이 없어 카드와 규칙이 다르다.
 *
 * <p><b>{@code summary}가 {@code null}일 수 있다.</b> 요약에는 템플릿이 없어 후검증 재생성 후에도 걸리면
 * {@code narrativeSource}가 {@code NONE}이고 문장이 비며, 조회는 그 짝을 {@code UNAVAILABLE}로 읽는다
 * (§C-4·§C-8). 컬럼을 {@code NOT NULL}로 조이면 그 경우가 저장되지 않는다.
 *
 * <p><b>{@code items}를 저장하지 않는다</b> — 조회 시 §C-2 구간으로 다시 질의한다(§데이터 모델). 목록을 담을
 * 컬럼이 없는 것이 의도다.
 */
@Entity
@Table(name = "instrument_news_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstrumentNewsSummary {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Column(name = "origin_trade_date", nullable = false)
	private LocalDate originTradeDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NewsSummaryScope scope;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private InstrumentNewsSummary(
		Instrument instrument,
		LocalDate originTradeDate,
		NewsSummaryScope scope,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		this.instrument = instrument;
		this.originTradeDate = originTradeDate;
		this.scope = scope;
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}

	/**
	 * @param originTradeDate 주식은 원본 거래일, 코인은 배치 실행 시점의 KST 날짜다. 코인도 비우지 않는다 (§C-9)
	 * @param summary         후검증 재생성 후에도 걸렸으면 {@code null}이고, 그때 {@code narrativeSource}가
	 *                        {@code NONE}이다 (§C-4)
	 */
	public static InstrumentNewsSummary create(
		Instrument instrument,
		LocalDate originTradeDate,
		NewsSummaryScope scope,
		String summary,
		NarrativeSource narrativeSource,
		LocalDateTime generatedAt) {
		return new InstrumentNewsSummary(instrument, originTradeDate, scope, summary, narrativeSource, generatedAt);
	}

	/**
	 * 같은 행의 서술을 갈아 끼운다 — <b>코인 전용</b>이다.
	 *
	 * <p>코인은 같은 {@code (종목, 그날 KST 날짜, ROLLING_24H)} 행을 매시 갱신해 하루 1행을 유지한다(§C-9).
	 * <b>주식은 이 경로를 쓰지 않는다</b> — 같은 서비스 날짜에 배치가 두 번 돌아도 중복이 생기지 않아야 하고
	 * (배치 ⑤) 이미 만든 요약을 다시 만들 이유가 없어 "존재 시 건너뜀"이다.
	 *
	 * <p>유니크 축({@code instrument_id}·{@code origin_trade_date}·{@code scope})은 건드리지 않는다 —
	 * 바꾸면 갱신이 아니라 다른 행이 된다.
	 *
	 * @param summary 후검증 재생성 후에도 걸렸으면 {@code null}이고, 그때 {@code narrativeSource}가
	 *     {@code NONE}이다 (§C-4)
	 */
	public void refreshNarrative(String summary, NarrativeSource narrativeSource, LocalDateTime generatedAt) {
		this.summary = summary;
		this.narrativeSource = narrativeSource;
		this.generatedAt = generatedAt;
	}
}
