// 수집기가 돌려주는 기사 1건 — 저장 가능한 네 값만 담는다.
package com.finplay.api.domain.feedback.collector;

import java.time.LocalDateTime;

/**
 * <b>필드가 넷뿐인 것이 이 record의 전부다.</b> spec §정책 전제가 노출 범위를 제목·언론사·원문 URL·발행시각으로
 * 확정했고, 기사 본문은 물론 <b>네이버가 주는 요약 스니펫({@code description})도 담지 않는다.</b> 담을 컬럼도
 * 없다(§데이터 모델 — {@code market_news_items}에 본문 컬럼이 없다). 필드를 늘리면 저작권 경계가 조용히 넓어지므로
 * 여기에 스니펫·본문·썸네일을 추가하지 않는다.
 *
 * <p>{@code publishedAt}은 기사 발행 시각이며 <b>수집 시각이 아니다.</b> 수집 시각은 저장 주체가
 * {@code MarketNewsItem.create}의 {@code now}로 따로 넣는다(§데이터 모델).
 *
 * <p>수집 단계는 이 값을 <b>발행일자로 거르지 않는다</b>(FEED-001). 구간 필터는 조회·매칭 시점에만 건다.
 */
public record CollectedNewsDto(String title, String publisher, String url, LocalDateTime publishedAt) {
}
