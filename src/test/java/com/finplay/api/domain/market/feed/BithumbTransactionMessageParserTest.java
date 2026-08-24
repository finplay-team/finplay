// 고정된 샘플 페이로드로 BithumbTransactionMessageParser의 파싱 결과·다건 처리·무시 규칙·예외 무전파를 검증하는 @JsonTest다.
package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.domain.market.feed.BithumbTransactionMessageParser.CryptoTrade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class BithumbTransactionMessageParserTest {

	// 실제 운영 코드가 쓰는 것과 같은, Boot의 JacksonAutoConfiguration이 구성한 빈을 주입받는다 (ADR-0003 — 직렬화·역직렬화는 슬라이스에서 실제로 확인).
	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void parsesSingleTradeIntoSymbolPriceQuantityAndTradedAt() {
		String payload = """
			{
			  "type": "transaction",
			  "content": {
			    "list": [
			      {
			        "symbol": "BTC_KRW",
			        "contPrice": "91839000",
			        "contQty": "0.00016332",
			        "contDtm": "2026-08-06 15:37:40.154998"
			      }
			    ]
			  }
			}
			""";

		List<CryptoTrade> result = BithumbTransactionMessageParser.parse(objectMapper, payload);

		assertThat(result).hasSize(1);
		CryptoTrade trade = result.get(0);
		assertThat(trade.symbol()).isEqualTo("BTC");
		assertThat(trade.price()).isEqualByComparingTo(new BigDecimal("91839000"));
		assertThat(trade.quantity()).isEqualByComparingTo(new BigDecimal("0.00016332"));
		assertThat(trade.tradedAt()).isEqualTo(LocalDateTime.of(2026, 8, 6, 15, 37, 40, 154998000));
	}

	@Test
	void parsesAllTradesWhenListHasMultipleEntries() {
		String payload = """
			{
			  "type": "transaction",
			  "content": {
			    "list": [
			      {"symbol": "BTC_KRW", "contPrice": "91839000", "contQty": "0.001", "contDtm": "2026-08-06 15:37:40.154998"},
			      {"symbol": "BTC_KRW", "contPrice": "91840000", "contQty": "0.002", "contDtm": "2026-08-06 15:37:41.000000"},
			      {"symbol": "BTC_KRW", "contPrice": "91841000", "contQty": "0.003", "contDtm": "2026-08-06 15:37:41.500000"}
			    ]
			  }
			}
			""";

		List<CryptoTrade> result = BithumbTransactionMessageParser.parse(objectMapper, payload);

		assertThat(result).hasSize(3);
		assertThat(result).extracting(CryptoTrade::price)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("91839000"), new BigDecimal("91840000"), new BigDecimal("91841000"));
	}

	@Test
	void ignoresNonTransactionTypeMessageWithoutThrowing() {
		String subscribeAck = """
			{
			  "status": "0000",
			  "resmsg": "Filter Registered Successfully"
			}
			""";

		List<CryptoTrade> result = BithumbTransactionMessageParser.parse(objectMapper, subscribeAck);

		assertThat(result).isEmpty();
	}

	@Test
	void ignoresTransactionMessageWithMissingListWithoutThrowing() {
		String malformed = """
			{
			  "type": "transaction",
			  "content": {}
			}
			""";

		List<CryptoTrade> result = BithumbTransactionMessageParser.parse(objectMapper, malformed);

		assertThat(result).isEmpty();
	}

	@Test
	void skipsEntireMessageWhenOneItemInListIsMalformed() {
		String payload = """
			{
			  "type": "transaction",
			  "content": {
			    "list": [
			      {"symbol": "BTC_KRW", "contPrice": "91839000", "contQty": "0.001", "contDtm": "2026-08-06 15:37:40.154998"},
			      {"symbol": "ETH_KRW", "contPrice": "not-a-number", "contQty": "0.5", "contDtm": "2026-08-06 15:37:41.000000"}
			    ]
			  }
			}
			""";

		List<CryptoTrade> result = BithumbTransactionMessageParser.parse(objectMapper, payload);

		assertThat(result).isEmpty();
	}

	@Test
	void neverThrowsEvenForCompletelyInvalidJson() {
		String invalidJson = "not a json payload";

		assertThatCode(() -> BithumbTransactionMessageParser.parse(objectMapper, invalidJson))
			.doesNotThrowAnyException();
	}
}
