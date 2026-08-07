// 빗썸 WebSocket transaction 메시지를 체결 목록(심볼·체결가·체결수량·체결시각)으로 파싱하는 순수 함수 — 인스턴스 상태 없이 샘플 페이로드만으로 단위 테스트 가능하다.
package com.finplay.api.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

// transaction 메시지의 정확한 JSON 필드 구성(content.list[].contPrice·contQty·contDtm·symbol)은 2026-08-06
// 실제 빗썸 WebSocket 연결로 확인했다(이슈 #242) — BithumbTickerMessageParser의 ticker와 달리 이 채널은
// Decision Gate가 아니라 확인된 사실이다. content.list는 배열이라 한 메시지에 체결이 여러 건 묶여 올 수 있다.
// 파싱 실패는 예외를 밖으로 던지지 않고 로그만 남긴 뒤 그 메시지 전체를 건너뛴다 — BithumbTickerMessageParser와
// 동일한 정책이다(다른 심볼·다른 메시지의 수신을 막지 않기 위함, 연결 유지).
@Slf4j
final class BithumbTransactionMessageParser {

	private static final String TRANSACTION_TYPE = "transaction";
	private static final String KRW_SUFFIX = "_KRW";
	// contDtm 예시: "2026-08-06 15:37:40.154998" (마이크로초 6자리, 실측 확인)
	private static final DateTimeFormatter TRADED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

	private BithumbTransactionMessageParser() {}

	// type이 "transaction"이 아닌 메시지(구독 ack 등)나 content.list가 없으면 빈 목록을 반환한다. list의
	// 원소 중 하나라도 파싱에 실패하면 그 메시지 전체를 건너뛴다(부분 반영하지 않음) — ticker와 동일하게
	// 이 메시지 자체가 손상됐다고 보는 것이 값을 일부만 반영해 잘못된 봉을 만드는 것보다 안전하다.
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

	// 파싱된 체결 한 건 — symbol은 _KRW 접미사가 제거된 Instrument.symbol 값이다.
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
