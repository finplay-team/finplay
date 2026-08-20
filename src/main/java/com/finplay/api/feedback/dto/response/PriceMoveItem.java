// 응답에 실리는 변동 원인 카드 1건 — 구간·변동률·서술과 근거 목록을 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * <b>{@code revealTime}을 담지 않는다</b> — 노출 여부를 서버가 판정한 뒤 통과한 카드만 응답에 넣으므로,
 * 그 값은 클라이언트가 알 필요가 없는 내부 판정값이다 ({@code docs/api/feedback.md}).
 *
 * <p><b>구간은 {@code LocalDateTime}으로 내린다.</b> 저장은 원본 거래일 시간축의 {@code TIME}이지만(§C-8),
 * 화면이 그리는 축은 날짜가 붙은 시각이라 계약이 {@code "2026-07-29T11:20:00"} 형태다. 날짜는 카드의
 * {@code originTradeDate}를 붙인다 — <b>조회한 날짜가 아니다.</b>
 *
 * <p>{@code sources}가 빈 배열인 카드는 존재하지 않는다 — 근거가 없으면 카드 자체를 만들지 않는다(FEED-003).
 */
public record PriceMoveItem(
	Long id,
	PriceMoveEventType eventType,
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	String narrative,
	List<NewsItem> sources) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (docs/agent-mistakes.md 2026-07-29).
	public PriceMoveItem {
		sources = List.copyOf(sources);
	}

	/**
	 * 주식 카드를 만든다. 코인 카드는 시각 컬럼이 달라({@code occurredAt} 하나, §C-9) 이 팩토리를 쓰지 않고
	 * {@link #ofCrypto}를 쓴다.
	 */
	public static PriceMoveItem ofStock(PriceMoveEvent event, List<NewsItem> sources) {
		LocalDate originTradeDate = event.getOriginTradeDate();
		return new PriceMoveItem(
			event.getId(),
			event.getEventType(),
			atOriginTradeDate(originTradeDate, event.getWindowStart()),
			atOriginTradeDate(originTradeDate, event.getWindowEnd()),
			event.getChangeRate(),
			event.getNarrative(),
			sources);
	}

	/**
	 * 코인 카드를 만든다 (§C-9). {@code windowEnd = occurredAt}이고 {@code windowStart = occurredAt −
	 * rolling-window-minutes}다 — 둘 다 이미 {@code LocalDateTime}이라 {@link #ofStock}의
	 * {@code atOriginTradeDate} 변환이 필요 없다.
	 *
	 * @param rollingWindowMinutes {@code feedback.crypto.rolling-window-minutes}(§C-7) — 카드 저장 시점의
	 *     설정값이 아니라 <b>조회 시점</b>의 현재 설정값을 쓴다({@code CryptoPriceMoveWatcher}와 같은 값을
	 *     참조하는 소스가 하나뿐이라 어긋나지 않는다)
	 */
	public static PriceMoveItem ofCrypto(
		PriceMoveEvent event, List<NewsItem> sources, int rollingWindowMinutes) {
		LocalDateTime windowEnd = event.getOccurredAt();
		return new PriceMoveItem(
			event.getId(),
			event.getEventType(),
			windowEnd.minusMinutes(rollingWindowMinutes),
			windowEnd,
			event.getChangeRate(),
			event.getNarrative(),
			sources);
	}

	private static LocalDateTime atOriginTradeDate(LocalDate originTradeDate, LocalTime time) {
		return time == null ? null : LocalDateTime.of(originTradeDate, time);
	}
}
