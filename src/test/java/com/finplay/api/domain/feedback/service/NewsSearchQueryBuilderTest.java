package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NewsSearchQueryBuilderTest {

	private final NewsSearchQueryBuilder queryBuilder = new NewsSearchQueryBuilder();

	@Test
	@DisplayName("코인 질의어에 심볼이 붙는다 — 트론 → \"트론 TRX\"")
	void appendsSymbolToCoinName() {
		assertThat(queryBuilder.build(crypto("TRX", "트론"))).isEqualTo("트론 TRX");
	}

	@ParameterizedTest(name = "{1} → \"{1} {0}\"")
	@CsvSource({"XRP,리플", "ADA,에이다", "TRX,트론", "DOT,폴카닷", "LINK,체인링크"})
	@DisplayName("일반명사·인명과 겹치는 코인명 전부에 심볼이 붙는다")
	void appendsSymbolToEveryAmbiguousCoinName(String symbol, String name) {
		assertThat(queryBuilder.build(crypto(symbol, name))).isEqualTo(name + " " + symbol);
	}

	@ParameterizedTest(name = "{1} → \"{1} {0}\"")
	@CsvSource({"BTC,비트코인", "DOGE,도지코인", "BCH,비트코인캐시"})
	@DisplayName("이름이 '코인'으로 끝나도 심볼을 그대로 붙인다 — \"비트코인 코인\" 같은 중복이 사라졌다")
	void appendsSymbolEvenWhenNameAlreadyEndsWithCoin(String symbol, String name) {
		assertThat(queryBuilder.build(crypto(symbol, name))).isEqualTo(name + " " + symbol);
	}

	@ParameterizedTest(name = "{0} → \"{0}\"")
	@ValueSource(strings = {"삼성전자", "SK하이닉스", "카카오", "NAVER"})
	@DisplayName("주식 질의어는 instruments.name 그대로다 — 보정이 붙지 않는다")
	void keepsStockNameAsQueryWithoutAnySuffix(String name) {
		assertThat(queryBuilder.build(stock("000000", name))).isEqualTo(name);
	}

	private static Instrument crypto(String symbol, String name) {
		return Instrument.create(
			Market.CRYPTO, symbol, name, new BigDecimal("1000"), 5000, true, LocalDateTime.now());
	}

	private static Instrument stock(String symbol, String name) {
		return Instrument.create(
			Market.STOCK, symbol, name, new BigDecimal("100"), 70000, true, LocalDateTime.now());
	}
}
