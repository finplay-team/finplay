// 종목별로 수집한 뉴스·공시 1건을 영속하는 엔티티 — 네 파트(카드 근거·매도 회고·요약·브리핑)가 이 테이블 하나를 공유한다.
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <b>기사 본문을 담는 컬럼이 없다.</b> 제목·언론사·원문 URL·발행시각까지만 저장하고 본문은 AI 입력으로만 쓰고
 * 버린다 (spec §정책 전제 — 저작권). 네이버 API가 주는 요약 스니펫도 저장하지 않는다.
 *
 * <p>{@code createdAt}은 발행 시각이 아니라 <b>수집 시각</b>이다 (§데이터 모델). 요약 재생성 판정이 이 값을
 * 직전 {@code generated_at}과 비교하므로(FEED-008) {@code publishedAt}과 하나로 합치지 않는다 — 수집이 30분
 * 주기라 10:03 발행 기사가 10:30에 저장되고, 발행 시각으로 비교하면 그 기사가 영영 요약에 들어가지 못한다.
 *
 * <p>유니크는 {@code (instrument_id, url)}이다. {@code url} 단독이 아닌 이유와 접두 길이를 두지 않는 이유는
 * §C-8·§데이터 모델에 있다.
 */
@Entity
@Table(name = "market_news_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketNewsItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MarketNewsItemType type;

	@Column(nullable = false, length = 500)
	private String title;

	@Column(nullable = false, length = 100)
	private String publisher;

	@Column(nullable = false, length = 500)
	private String url;

	@Column(name = "published_at", nullable = false)
	private LocalDateTime publishedAt;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private MarketNewsItem(
		Instrument instrument,
		MarketNewsItemType type,
		String title,
		String publisher,
		String url,
		LocalDateTime publishedAt,
		LocalDateTime createdAt) {
		this.instrument = instrument;
		this.type = type;
		this.title = title;
		this.publisher = publisher;
		this.url = url;
		this.publishedAt = publishedAt;
		this.createdAt = createdAt;
	}

	/**
	 * @param publishedAt 기사 발행 시각. 공시는 접수일자만 있어 {@code 00:00:00}이다 (§C-3)
	 * @param now         <b>수집 시각</b>이다. 발행 시각을 넣지 않는다
	 */
	public static MarketNewsItem create(
		Instrument instrument,
		MarketNewsItemType type,
		String title,
		String publisher,
		String url,
		LocalDateTime publishedAt,
		LocalDateTime now) {
		return new MarketNewsItem(instrument, type, title, publisher, url, publishedAt, now);
	}
}
