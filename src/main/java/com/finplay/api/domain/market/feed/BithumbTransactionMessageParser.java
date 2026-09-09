package com.finplay.api.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
final class BithumbTransactionMessageParser {

	private static final String TRANSACTION_TYPE = "transaction";
	private static final String KRW_SUFFIX = "_KRW";
	private static final DateTimeFormatter TRADED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

	private BithumbTransactionMessageParser() {}

	static List<CryptoTrade> parse(ObjectMapper objectMapper, String payload) {
		try {
			TransactionMessage message = objectMapper.readValue(payload, TransactionMessage.class);
			if (message == null || !TRANSACTION_TYPE.equals(message.type()) || message.content() == null
				|| message.content().list() == null) {
				return List.of();
			}
			return message.content().list().stream().map(BithumbTransactionMessageParser::toTrade).toList();
		} catch (Exception ex) {
			log.warn("빗썸 transaction 메시지 파싱 실패, 이 메시지를 건너뜁니다: {}", payload, ex);
			return List.of();
		}
	}

	private static CryptoTrade toTrade(TransactionItem item) {
		String symbol = stripKrwSuffix(item.symbol());
		BigDecimal price = new BigDecimal(item.contPrice());
		BigDecimal quantity = new BigDecimal(item.contQty());
		LocalDateTime tradedAt = LocalDateTime.parse(item.contDtm(), TRADED_AT_FORMAT);
		return new CryptoTrade(symbol, tradedAt, price, quantity);
	}

	private static String stripKrwSuffix(String symbol) {
		if (symbol == null) {
			throw new IllegalArgumentException("content.list[].symbol이 null입니다.");
		}
		return symbol.endsWith(KRW_SUFFIX) ? symbol.substring(0, symbol.length() - KRW_SUFFIX.length()) : symbol;
	}

	record CryptoTrade(String symbol, LocalDateTime tradedAt, BigDecimal price, BigDecimal quantity) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TransactionMessage(String type, TransactionContent content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TransactionContent(List<TransactionItem> list) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TransactionItem(String symbol, String contPrice, String contQty, String contDtm) {
	}
}
