// 변동 카드 1건에 대한 집단 행동 집계를 영속하는 엔티티 — 장 마감 배치가 확정 집계를 저장한다.
package com.finplay.api.feedback.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <b>회원 식별자를 담지 않는다</b> — 집계 결과만 저장한다 (§데이터 모델).
 *
 * <p>유니크가 {@code (price_move_event_id, service_date)}인 이유는 재재생이다 (§C-9). 같은 원본 거래일이 두 번
 * 재생되면 카드 행이 재사용되므로 {@code price_move_event_id} 단독이면 두 번째 재생일 집계가 첫 번째를 덮어써
 * <b>첫날 매도자의 통계가 사라지고 둘째 날 사용자는 남의 날 통계로 서술이 굳는다.</b> 조회는 그 체결의 서비스
 * 날짜 행만 본다.
 *
 * <p>{@code medianMinutesToSell}이 {@code null}이면 <b>보유자 전원이 그 구간에 팔지 않았다</b>는 뜻이다
 * (§C-8). 0이 아니라 {@code null}인 것이 의도이며, 표본 부족({@code holderCount < 5}) 판정은 조회 시점이
 * 담당한다(§C-4).
 */
@Entity
@Table(name = "price_move_peer_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMovePeerStat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "price_move_event_id", nullable = false)
	private PriceMoveEvent priceMoveEvent;

	@Column(name = "service_date", nullable = false)
	private LocalDate serviceDate;

	@Column(name = "holder_count", nullable = false)
	private int holderCount;

	@Column(name = "sold_within_30min_count", nullable = false)
	private int soldWithin30MinCount;

	@Column(name = "median_minutes_to_sell")
	private Integer medianMinutesToSell;

	@Column(name = "aggregated_at", nullable = false)
	private LocalDateTime aggregatedAt;

	private PriceMovePeerStat(
		PriceMoveEvent priceMoveEvent,
		LocalDate serviceDate,
		int holderCount,
		int soldWithin30MinCount,
		Integer medianMinutesToSell,
		LocalDateTime aggregatedAt) {
		this.priceMoveEvent = priceMoveEvent;
		this.serviceDate = serviceDate;
		this.holderCount = holderCount;
		this.soldWithin30MinCount = soldWithin30MinCount;
		this.medianMinutesToSell = medianMinutesToSell;
		this.aggregatedAt = aggregatedAt;
	}

	/**
	 * @param serviceDate           집계를 만든 <b>서비스 날짜</b>다. 원본 거래일이 아니다 (§C-9)
	 * @param medianMinutesToSell   보유자 전원이 미매도면 {@code null}이다
	 */
	public static PriceMovePeerStat create(
		PriceMoveEvent priceMoveEvent,
		LocalDate serviceDate,
		int holderCount,
		int soldWithin30MinCount,
		Integer medianMinutesToSell,
		LocalDateTime aggregatedAt) {
		return new PriceMovePeerStat(
			priceMoveEvent, serviceDate, holderCount, soldWithin30MinCount, medianMinutesToSell, aggregatedAt);
	}
}
