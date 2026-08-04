// 제목에 같은 시장의 다른 종목명이 든 기사를 거르는 규칙이 spec 012 FEED-001대로 동작하는지 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 기대값의 정본은 spec.md FEED-001이다 — "이더리움·비트코인 질의는 이더리움클래식·비트코인캐시 기사를 함께
// 끌어온다. 제목에 같은 시장의 다른 instruments.name이 포함된 기사는 저장 시 제외한다."
//
// 종목명은 V7 시드(§C-7의 주식 16·코인 12)를 그대로 쓴다. 목록에는 대상 자신의 이름도 들어 있다 — 호출부가
// 목록에서 대상을 빼는 손질을 하지 않아도 결과가 같아야 하기 때문이다.
class NewsTitleFilterTest {

	private static final List<String> CRYPTO_NAMES = List.of(
		"비트코인", "이더리움", "리플", "솔라나", "도지코인", "에이다",
		"트론", "아발란체", "체인링크", "폴카닷", "비트코인캐시", "이더리움클래식");

	private static final List<String> STOCK_NAMES = List.of(
		"삼성전자", "SK하이닉스", "LG에너지솔루션", "삼성바이오로직스", "현대차", "기아",
		"셀트리온", "NAVER", "POSCO홀딩스", "삼성SDI", "LG화학", "KB금융",
		"신한지주", "카카오", "현대모비스", "SK이노베이션");

	private final NewsTitleFilter titleFilter = new NewsTitleFilter();

	// FEED-001이 예로 든 접두 관계 종목쌍의 양방향이다. 같은 제목을 짧은 이름 종목으로 볼 때와 긴 이름 종목으로
	// 볼 때 결과가 반대여야 한다 — 단순 포함 검사로 판정하면 둘 중 한쪽이 반드시 틀린다.
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

	// 자기 이름이 앞에 한 번 나왔다고 통과시키면 안 된다. 뒤쪽 언급은 자기 이름 등장 구간에 덮이지 않는
	// 독립 언급이라 다른 종목 기사다.
	@Test
	@DisplayName("자기 이름이 먼저 나와도 뒤에 나온 다른 종목명 때문에 제외된다")
	void excludesWhenAnotherNameAppearsAfterOwnName() {
		String title = "비트코인 강세, 비트코인캐시도 동반 급등";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), CRYPTO_NAMES, title)).isFalse();
	}

	// 반대 방향도 같다 — 긴 이름 종목이라도 짧은 형제가 자기 이름 밖에서 독립적으로 언급되면 제외다.
	@Test
	@DisplayName("긴 이름 종목도 짧은 형제가 독립적으로 언급되면 제외된다")
	void excludesLongerNameInstrumentWhenShorterSiblingIsMentionedIndependently() {
		String title = "비트코인캐시 급등, 비트코인도 사상 최고가";

		assertThat(titleFilter.isRelevant(crypto("비트코인캐시"), CRYPTO_NAMES, title)).isFalse();
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

	// 유일한 제외 사유는 "같은 시장의 다른 종목명"이다. 수집 단계에서 제목으로 관련성을 다시 판정하지 않는다.
	@Test
	@DisplayName("다른 종목명이 없으면 자기 이름이 제목에 없어도 남는다")
	void keepsArticleThatMentionsNoInstrumentNameAtAll() {
		String title = "가상자산 시장 전반 강세 지속";

		assertThat(titleFilter.isRelevant(crypto("이더리움"), CRYPTO_NAMES, title)).isTrue();
	}

	// 호출부가 목록에서 대상을 빼는 손질을 하지 않아도 결과가 같아야 한다.
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
	@DisplayName("같은 시장 종목명 목록이 비어 있으면 제외하지 않는다")
	void keepsArticleWhenSameMarketNamesIsEmpty() {
		String title = "비트코인캐시 급등에 거래량 3배";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), List.of(), title)).isTrue();
	}

	// V7 시드 주식 16종에는 한 이름이 다른 이름에 통째로 들어가는 접두쌍이 없다. 대신 실재하는 위험은 상호명
	// 접두를 공유하는 쌍(삼성전자·삼성SDI·삼성바이오로직스)이다 — 공유 접두를 종목명처럼 취급하면 자기 기사가
	// 통째로 사라진다.
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

	// 판정 대상은 넘겨받은 같은 시장 목록뿐이다. 시장을 섞어 코인명까지 넘기면 이 기사는 사라진다 —
	// 그래서 호출부가 같은 시장 목록만 넘긴다(FEED-001·§C-6).
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
