// 응답에 실리는 근거 기사·공시 1건 — 제목·언론사·원문 URL·발행시각까지만 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import java.time.LocalDateTime;

/**
 * <b>본문과 요약 스니펫을 담지 않는다</b> (spec §정책 전제 — 저작권). 노출 범위는 네 값뿐이고 원문 링크로
 * 트래픽을 언론사에 보내는 구조다. 저장 자체가 이 네 값뿐이라({@code MarketNewsItem}) 담을 것도 없다.
 *
 * <p>{@code publisher}가 뉴스에서 {@code hankyung.com}처럼 도메인인 것은 오타가 아니다 — 네이버 뉴스 검색
 * 응답에 언론사 이름 필드가 없어 {@code originallink} 호스트를 쓴다(§C-8). 공시는 {@code DART} 고정이다.
 *
 * <p>카드·요약·브리핑·매도 회고가 같은 형태를 쓰므로 특정 응답 안에 중첩하지 않고 {@code dto/response/}에
 * 둔다 (§C-6의 중첩 레코드 목록).
 */
public record NewsItem(
	MarketNewsItemType type,
	String title,
	String publisher,
	String url,
	LocalDateTime publishedAt) {

	public static NewsItem from(MarketNewsItem item) {
		return new NewsItem(
			item.getType(), item.getTitle(), item.getPublisher(), item.getUrl(), item.getPublishedAt());
	}
}
