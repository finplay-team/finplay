// 변동 구간 카드와 그 근거 기사의 연결(N:M) 1건을 영속하는 엔티티
package com.finplay.api.domain.feedback.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기사 하나가 여러 카드의 근거가 되고 카드 하나가 여러 기사를 근거로 두므로 연결 테이블로 분리했다. 같은 쌍을
 * 두 번 넣지 못하게 {@code (price_move_event_id, market_news_item_id)}에 유니크가 걸려 있다 (§데이터 모델).
 *
 * <p>근거가 하나도 없으면 <b>카드 자체를 만들지 않는다</b>(FEED-003). 따라서 이 행이 0건인 카드는 존재하지
 * 않아야 하며, 그 판정은 카드를 만드는 쪽 책임이다.
 */
@Entity
@Table(name = "price_move_event_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceMoveEventSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "price_move_event_id", nullable = false)
	private PriceMoveEvent priceMoveEvent;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "market_news_item_id", nullable = false)
	private MarketNewsItem marketNewsItem;

	private PriceMoveEventSource(PriceMoveEvent priceMoveEvent, MarketNewsItem marketNewsItem) {
		this.priceMoveEvent = priceMoveEvent;
		this.marketNewsItem = marketNewsItem;
	}

	public static PriceMoveEventSource of(PriceMoveEvent priceMoveEvent, MarketNewsItem marketNewsItem) {
		return new PriceMoveEventSource(priceMoveEvent, marketNewsItem);
	}
}
