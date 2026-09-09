package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NewsTitleFilterTest {

	private static final List<String> CRYPTO_NAMES = List.of(
		"비트코인", "이더리움", "리플", "솔라나", "도지코인", "에이다",
		"트론", "아발란체", "체인링크", "폴카닷", "비트코인캐시", "이더리움클래식");

	private static final List<String> STOCK_NAMES = List.of(
		"삼성전자", "SK하이닉스", "LG에너지솔루션", "삼성바이오로직스", "현대차", "기아",
		"셀트리온", "NAVER", "POSCO홀딩스", "삼성SDI", "LG화학", "KB금융",
		"신한지주", "카카오", "현대모비스", "SK이노베이션");

	private final NewsTitleFilter titleFilter = new NewsTitleFilter();

	@ParameterizedTest(name = "[{0}] 수집 중 제목 \"{1}\" → 저장 {2}")
	@CsvSource({
		"비트코인,       비트코인캐시 급등에 거래량 3배,   false",
		"비트코인캐시,   비트코인캐시 급등에 거래량 3배,   true",
		"이더리움,       이더리움클래식 하드포크 완료,     false",
		"이더리움클래식, 이더리움클래식 하드포크 완료,     true"
	})
	@DisplayName("접두 관계 종목쌍은 양방향으로 갈린다 — 짧은 이름은 제외, 긴 이름은 유지")
	void separatesPrefixSiblingsInBothDirections(String selfName, String title, boolean expected) {
		Instrument self = crypto(selfName);

		assertThat(titleFilter.isRelevant(self, CRYPTO_NAMES, title)).isEqualTo(expected);
	}

	@Test
	@DisplayName("[개정] 다른 종목명이 함께 있어도 자기 이름이 독립적으로 보이면 남는다")
	void keepsCoinArticleThatAlsoMentionsAnotherCoinName() {
		String title = "비트코인 강세, 비트코인캐시도 동반 급등";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), CRYPTO_NAMES, title)).isTrue();
	}

	@Test
	@DisplayName("[개정] 긴 이름 종목도 짧은 형제가 함께 언급된 제목에서 남는다")
	void keepsLongerNameCoinWhenShorterSiblingIsAlsoMentioned() {
		String title = "비트코인캐시 급등, 비트코인도 사상 최고가";

		assertThat(titleFilter.isRelevant(crypto("비트코인캐시"), CRYPTO_NAMES, title)).isTrue();
	}

	@Test
	@DisplayName("[개정] 자기 이름의 첫 등장이 긴 형제에 삼켜져도 뒤의 독립 등장을 찾아낸다")
	void findsIndependentSelfOccurrenceAfterSwallowedFirstOne() {
		String title = "비트코인캐시 급등, 비트코인도 사상 최고가";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), CRYPTO_NAMES, title)).isTrue();
	}

	@Test
	@DisplayName("[개정] 자기 이름 등장이 각기 다른 형제 등장에 모두 삼켜지면 제외된다")
	void excludesWhenEverySelfOccurrenceIsSwallowedByADifferentSiblingOccurrence() {
		String title = "비트코인캐시 급등, 비트코인캐시 신고가";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), CRYPTO_NAMES, title)).isFalse();
	}

	@Test
	@DisplayName("접두 관계가 아닌 다른 종목명이 제목에 있으면 제외된다")
	void excludesArticleThatMentionsUnrelatedInstrumentName() {
		String title = "비트코인 사상 최고가 경신";

		assertThat(titleFilter.isRelevant(crypto("이더리움"), CRYPTO_NAMES, title)).isFalse();
	}

	@Test
	@DisplayName("자기 종목명만 든 제목은 남는다")
	void keepsArticleThatMentionsOnlyOwnName() {
		String title = "이더리움 대규모 업그레이드 완료";

		assertThat(titleFilter.isRelevant(crypto("이더리움"), CRYPTO_NAMES, title)).isTrue();
	}

	@Test
	@DisplayName("[개정] 자기 이름이 제목에 없으면 다른 종목명이 없어도 제외된다")
	void excludesCoinArticleThatMentionsNoInstrumentNameAtAll() {
		String title = "가상자산 시장 전반 강세 지속";

		assertThat(titleFilter.isRelevant(crypto("이더리움"), CRYPTO_NAMES, title)).isFalse();
	}

	@ParameterizedTest(name = "[{0}] \"{1}\" → 제외")
	@CsvSource({
		"체인링크, 두바이듀티프리 암호화폐 결제 디르함 정산이 핵심",
		"체인링크, 써클 아크 9월16일 출범…블랙록·비자 등 검증인 합류",
		"비트코인캐시, 260레인 CXL 스위치 마벨 AI 메모리 병목 겨냥"
	})
	@DisplayName("[개정] 실측에서 통과하던 무관 기사가 제외된다")
	void excludesRealWorldIrrelevantTitlesObservedInMeasurement(String selfName, String title) {
		assertThat(titleFilter.isRelevant(crypto(selfName), CRYPTO_NAMES, title)).isFalse();
	}

	@Test
	@DisplayName("sameMarketNames에 대상 자신의 이름이 있어도 자기 기사가 제외되지 않는다")
	void ignoresOwnNameInsideSameMarketNames() {
		Instrument self = crypto("비트코인");
		String title = "비트코인 기관 자금 유입 확대";
		List<String> withoutSelf = CRYPTO_NAMES.stream()
			.filter(name -> !name.equals("비트코인"))
			.toList();

		assertThat(CRYPTO_NAMES).contains("비트코인");
		assertThat(titleFilter.isRelevant(self, CRYPTO_NAMES, title)).isTrue();
		assertThat(titleFilter.isRelevant(self, withoutSelf, title))
			.isEqualTo(titleFilter.isRelevant(self, CRYPTO_NAMES, title));
	}

	@Test
	@DisplayName("같은 시장 종목명 목록이 비면 접두 보호가 사라진다 — 호출부가 목록을 넘기는 것이 전제다")
	void losesPrefixProtectionWhenSameMarketNamesIsEmpty() {
		String title = "비트코인캐시 급등에 거래량 3배";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), List.of(), title)).isTrue();
	}

	@Test
	@DisplayName("상호명 접두를 공유하는 주식은 자기 이름만 든 제목이 남는다")
	void keepsStockArticleWhenOnlyOwnNameAppearsAmongSharedPrefixSiblings() {
		String title = "삼성전자 4분기 영업이익 시장 전망 상회";

		assertThat(titleFilter.isRelevant(stock("삼성전자"), STOCK_NAMES, title)).isTrue();
	}

	@Test
	@DisplayName("주식도 같은 시장의 다른 종목명이 제목에 있으면 제외된다")
	void excludesStockArticleThatMentionsAnotherStockName() {
		String title = "삼성전자·삼성SDI 동반 상승";

		assertThat(titleFilter.isRelevant(stock("삼성전자"), STOCK_NAMES, title)).isFalse();
	}

	@Test
	@DisplayName("다른 시장의 종목명은 판정에 쓰이지 않는다")
	void judgesOnlyWithTheGivenSameMarketNames() {
		String title = "카카오, 비트코인 결제 도입 검토";

		assertThat(titleFilter.isRelevant(stock("카카오"), STOCK_NAMES, title)).isTrue();
	}

	private static Instrument crypto(String name) {
		return Instrument.create(
			Market.CRYPTO, "SYM", name, new BigDecimal("1000"), 5000, true, LocalDateTime.now());
	}

	private static Instrument stock(String name) {
		return Instrument.create(
			Market.STOCK, "000000", name, new BigDecimal("100"), 70000, true, LocalDateTime.now());
	}
}
