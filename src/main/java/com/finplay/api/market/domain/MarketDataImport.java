// KIS 과거 분봉 수집 시도 이력(성공·부분성공·전체실패·중복스킵)을 표현하는 엔티티. stock_candles 저장 성공 여부와 무관하게 남는다.
package com.finplay.api.market.domain;

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

@Entity
@Table(name = "market_data_imports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketDataImport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source", nullable = false, length = 50)
	private String source;

	@Column(name = "source_trading_date", nullable = false)
	private LocalDate sourceTradingDate;

	@Column(name = "collected_at", nullable = false)
	private LocalDateTime collectedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ImportStatus status;

	@Column(name = "failure_reason", length = 500)
	private String failureReason;

	private MarketDataImport(
		String source,
		LocalDate sourceTradingDate,
		LocalDateTime collectedAt,
		ImportStatus status,
		String failureReason) {
		this.source = source;
		this.sourceTradingDate = sourceTradingDate;
		this.collectedAt = collectedAt;
		this.status = status;
		this.failureReason = failureReason;
	}

	// 상태별 nullable 제약이 없어(spec.md에 별도 규칙 없음) 단순 팩토리 하나로 둔다 — 상태별 분리는 실제로 필요해질 때(YAGNI).
	public static MarketDataImport create(
		String source, LocalDate sourceTradingDate, LocalDateTime collectedAt, ImportStatus status,
		String failureReason) {
		return new MarketDataImport(source, sourceTradingDate, collectedAt, status, failureReason);
	}
}
