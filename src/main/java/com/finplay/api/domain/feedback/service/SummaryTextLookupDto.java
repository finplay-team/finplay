// 요약·브리핑 행 조회 결과를 트랜잭션 밖으로 내보내는 내부 DTO — "행이 있었는가"와 "서술"을 함께 담는다.
package com.finplay.api.domain.feedback.service;

import java.util.Optional;

/**
 * <b>왜 {@code Optional<String>} 하나로는 안 되는가.</b> §C-4는 4번({@code EMPTY} — 행이 없다)과 5번
 * ({@code UNAVAILABLE} — 행은 있는데 {@code summary}가 {@code null}이다)을 <b>행 존재 여부로</b> 가르는데,
 * 서술만 돌려주면 둘 다 "없음"이라 구분이 사라진다. 그래서 두 값을 함께 넘긴다.
 *
 * <p>종목 뉴스 요약과 시장 브리핑이 같은 record를 쓴다 — 담는 엔티티는 다르지만 <b>§C-4 4·5번 판정에 필요한
 * 모양이 같기 때문</b>이고, 그 판정이 두 경로에서 같아야 한다는 것 자체가 계약이다(spec §C-4).
 *
 * <p>엔티티를 트랜잭션 밖으로 내보내지 않으려고 {@code Reader}가 경계 안에서 이 형태로 바꿔 돌려준다.
 */
public record SummaryTextLookupDto(boolean rowExists, String text) {

	/** 행이 아예 없다 — §C-4 4번({@code EMPTY}). */
	public static SummaryTextLookupDto missingRow() {
		return new SummaryTextLookupDto(false, null);
	}

	/** 행은 있다. {@code text}가 {@code null}이면 §C-4 5번({@code UNAVAILABLE})이다. */
	public static SummaryTextLookupDto row(String text) {
		return new SummaryTextLookupDto(true, text);
	}

	/**
	 * 캐시가 담는 값의 모양이다 — <b>서술이 있는 경우만</b> 값이 있다. 캐시는 이 형태로만 저장하므로
	 * 캐시 적중은 곧 {@code READY}이고, 미적중이면 위 {@code rowExists}로 4·5번을 가른다.
	 */
	public Optional<String> readyText() {
		return Optional.ofNullable(text);
	}
}
