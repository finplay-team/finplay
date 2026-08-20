// 개장 전 브리핑 응답의 기사·공시 1건 — 시장 전체가 대상이라 종목 정보를 평평하게 함께 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import java.time.LocalDateTime;

/**
 * <b>{@code NewsItem}과 따로 두는 것이 의도된 형태다</b> (spec §C-6, 2026-08-04 확정). 계약이
 * {@code NewsItem}의 다섯 값에 {@code instrumentId}·{@code symbol}·{@code name}을 <b>평평하게</b> 더한 여덟
 * 값을 요구하므로 재사용으로는 계약을 만족할 수 없고, {@code NewsItem}을 중첩 필드로 감싸면 <b>JSON 모양이
 * 계약과 달라진다.</b> Part C는 종목이 경로에 있어 그 세 값이 필요 없으므로 {@code NewsItem}을 그대로 쓴다 —
 * 두 항목 record의 공존이 의도이며 한쪽으로 합치지 않는다.
 *
 * <p>종목 정보를 함께 싣는 이유는 화면이 추가 조회를 하지 않아도 되게 하기 위해서다
 * ({@code docs/api/feedback.md}).
 *
 * <p><b>이름에 {@code ~ListItemResponse}를 붙이지 않는다</b> — {@code docs/conventions/code.md} DTO 표 각주가
 * spec 012 항목 record의 이름을 §C-6에 위임했고, 같은 응답군에서 접미사가 섞이면 그 위임이 무의미해진다.
 *
 * <p>본문과 요약 스니펫을 담지 않는 이유, {@code publisher}가 뉴스에서 도메인인 이유는 {@code NewsItem}과 같다.
 */
public record BriefingNewsItem(
	Long instrumentId,
	String symbol,
	String name,
	MarketNewsItemType type,
	String title,
	String publisher,
	String url,
	LocalDateTime publishedAt) {

	public static BriefingNewsItem from(MarketNewsItem item) {
		return new BriefingNewsItem(
			item.getInstrument().getId(),
			item.getInstrument().getSymbol(),
			item.getInstrument().getName(),
			item.getType(),
			item.getTitle(),
			item.getPublisher(),
			item.getUrl(),
			item.getPublishedAt());
	}
}
