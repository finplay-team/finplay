// 빗썸 WebSocket ticker 메시지를 심볼·가격·수신시각으로 파싱하는 순수 함수 — 인스턴스 상태 없이 샘플 페이로드만으로 단위 테스트 가능하다.
package com.finplay.api.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

// ticker 메시지의 정확한 JSON 필드 구성(content.symbol·content.closePrice·content.date·content.time)은 실제 빗썸
// WebSocket 연결로 확인된 적이 없는 미확정 항목이다(Decision Gate — KisHistoricalCandleClientImpl의 output2 필드명과
// 같은 성격, spec.md·plan.md·tasks.md 이슈 #104 참고). 공개 문서 기준 최선 추정으로 구현했으며, 실제 응답과 다르면
// 이 클래스(특히 TickerMessage·TickerContent record와 parse 메서드)만 교정하면 된다. 파싱 실패는 예외를 밖으로
// 던지지 않고 로그만 남긴 뒤 해당 메시지를 건너뛴다 — 다른 정상 심볼의 수신을 막지 않기 위함(연결 유지).
@Slf4j
final class BithumbTickerMessageParser {

	private static final String TICKER_TYPE = "ticker";
	private static final String KRW_SUFFIX = "_KRW";
	private static final DateTimeFormatter RECEIVED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private BithumbTickerMessageParser() {}

	// type이 "ticker"가 아닌 메시지(구독 확인 응답 등)는 무시하고 빈 Optional을 반환한다. 필드 누락·타입 불일치 등
	// 어떤 파싱 예외가 나도 여기서 잡아 로그만 남기고 빈 Optional로 반환한다(연결 자체는 끊지 않기 위함).
	static Optional<BithumbTick> parse(ObjectMapper objectMapper, String payload) {
		try {
			TickerMessage message = objectMapper.readValue(payload, TickerMessage.class);
			if (message == null || !TICKER_TYPE.equals(message.type()) || message.content() == null) {
				return Optional.empty();
			}
			return Optional.of(toTick(message.content()));
		} catch (Exception ex) {
			log.warn("빗썸 ticker 메시지 파싱 실패, 이 메시지를 건너뜁니다: {}", payload, ex);
			return Optional.empty();
		}
	}

	private static BithumbTick toTick(TickerContent content) {
		String symbol = stripKrwSuffix(content.symbol());
		BigDecimal price = new BigDecimal(content.closePrice());
		LocalDateTime receivedAt = LocalDateTime.parse(content.date() + content.time(), RECEIVED_AT_FORMAT);
		return new BithumbTick(symbol, price, receivedAt);
	}

	private static String stripKrwSuffix(String symbol) {
		if (symbol == null) {
			throw new IllegalArgumentException("content.symbol이 null입니다.");
		}
		return symbol.endsWith(KRW_SUFFIX) ? symbol.substring(0, symbol.length() - KRW_SUFFIX.length()) : symbol;
	}

	// 파싱된 ticker 한 건 — symbol은 _KRW 접미사가 제거된 Instrument.symbol 값이다.
	record BithumbTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TickerMessage(String type, TickerContent content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TickerContent(String symbol, String closePrice, String date, String time) {
	}
}
