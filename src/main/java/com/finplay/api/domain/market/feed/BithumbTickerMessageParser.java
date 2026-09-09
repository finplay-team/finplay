package com.finplay.api.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
final class BithumbTickerMessageParser {

	private static final String TICKER_TYPE = "ticker";
	private static final String KRW_SUFFIX = "_KRW";
	private static final DateTimeFormatter RECEIVED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

	private BithumbTickerMessageParser() {}

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

	record BithumbTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TickerMessage(String type, TickerContent content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TickerContent(String symbol, String closePrice, String date, String time) {
	}
}
