// 종목별 뉴스 검색 질의어 조립이 spec 012 FEED-001의 코인 보정 규칙을 따르는지 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 기대값의 정본은 spec.md FEED-001이다 — "코인 질의어는 instruments.name에 " 코인"을 붙인다 (예: 비트코인 코인)",
// 그리고 보정은 코인에만 붙는다(주식 질의어는 instruments.name 그대로). 외부 의존이 없는 순수 계산이라
// 고정 픽스처로 단정한다(§C-6).
class NewsSearchQueryBuilderTest {

	private final NewsSearchQueryBuilder queryBuilder = new NewsSearchQueryBuilder();

	@Test
	@DisplayName("코인 질의어에 FEED-001의 보정이 붙는다 — 비트코인 → \"비트코인 코인\"")
	void appendsCryptoSuffixToCoinName() {
		assertThat(queryBuilder.build(crypto("BTC", "비트코인"))).isEqualTo("비트코인 코인");
	}

	// FEED-001이 보정의 근거로 직접 든 이름들이다 — 일반명사·인명과 충돌해 이름만으로는 무관 기사가 붙는다.
	@ParameterizedTest(name = "{0} → \"{0} 코인\"")
	@ValueSource(strings = {"리플", "에이다", "트론", "폴카닷", "체인링크"})
	@DisplayName("일반명사·인명과 겹치는 코인명 전부에 보정이 붙는다")
	void appendsCryptoSuffixToEveryAmbiguousCoinName(String name) {
		assertThat(queryBuilder.build(crypto("SYM", name))).isEqualTo(name + " 코인");
	}

	// FEED-001의 예가 곧 이 경우다 — 비트코인은 이미 '코인'으로 끝나지만 예시 결과가 "비트코인 코인"이다.
	// 이름이 '코인'으로 끝날 때 보정을 생략하는 예외는 spec에 없다.
	@ParameterizedTest(name = "{0} → \"{0} 코인\"")
	@ValueSource(strings = {"비트코인", "도지코인", "비트코인캐시"})
	@DisplayName("이름이 이미 '코인'으로 끝나도 보정을 생략하지 않는다")
	void appendsCryptoSuffixEvenWhenNameAlreadyEndsWithCoin(String name) {
		assertThat(queryBuilder.build(crypto("SYM", name))).isEqualTo(name + " 코인");
	}

	// FEED-001 — 보정은 코인에만 붙는다.
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
