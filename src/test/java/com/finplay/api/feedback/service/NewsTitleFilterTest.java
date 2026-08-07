// 제목으로 그 종목의 기사인지 가리는 규칙이 spec 012 FEED-001대로 동작하는지 검증한다 (시장마다 규칙이 다르다).
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

// 기대값의 정본은 spec.md FEED-001이고 시장마다 규칙이 다르다 (2026-08-07 개정, 이슈 #179).
//
//   코인  자기 이름이 독립적으로 등장하면 통과   — 다른 종목명이 함께 있어도 무관하다
//   주식  다른 종목명이 독립적으로 등장하면 제외 — 자기 이름이 있는지는 보지 않는다 (개정 전 규칙 유지)
//
// 주식을 그대로 둔 것은 실측하지 않았기 때문이다(C-005). 아래 주식 테스트 셋이 개정 전과 같은 기대값으로
// 남아 있는 것이 그 증거다 — 코인만 갈렸다는 것을 이 파일 안에서 대조할 수 있다.
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

	// 2026-08-07 개정(이슈 #179)으로 결과가 뒤집힌 자리다. 개정 전에는 제외했다.
	//
	// 암호화폐 기사는 여러 코인을 한 문장에 나열하는 것이 기본형이라("비트코인 63,340달러, 이더리움 …"),
	// 다른 이름이 있다고 제외하면 시세 브리핑이 통째로 사라진다. 자기 이름이 제목에 온전히 보이면 그 종목의
	// 기사가 맞다.
	@Test
	@DisplayName("[개정] 다른 종목명이 함께 있어도 자기 이름이 독립적으로 보이면 남는다")
	void keepsCoinArticleThatAlsoMentionsAnotherCoinName() {
		String title = "비트코인 강세, 비트코인캐시도 동반 급등";

		assertThat(titleFilter.isRelevant(crypto("비트코인"), CRYPTO_NAMES, title)).isTrue();
	}

	// 반대 방향도 같다 — 긴 이름 종목도 자기 이름이 보이면 짧은 형제가 함께 언급돼도 남는다.
	@Test
	@DisplayName("[개정] 긴 이름 종목도 짧은 형제가 함께 언급된 제목에서 남는다")
	void keepsLongerNameCoinWhenShorterSiblingIsAlsoMentioned() {
		String title = "비트코인캐시 급등, 비트코인도 사상 최고가";

		assertThat(titleFilter.isRelevant(crypto("비트코인캐시"), CRYPTO_NAMES, title)).isTrue();
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

	// 2026-08-07 개정(이슈 #179)으로 결과가 뒤집힌 자리다. 개정 전에는 남겼다 — 그것이 오탐의 통로였다.
	//
	// 개정 전 규칙은 자기 이름을 아예 보지 않아, 통과한 680건 중 535건(79%)의 제목에 그 코인 이름이 없었다.
	@Test
	@DisplayName("[개정] 자기 이름이 제목에 없으면 다른 종목명이 없어도 제외된다")
	void excludesCoinArticleThatMentionsNoInstrumentNameAtAll() {
		String title = "가상자산 시장 전반 강세 지속";

		assertThat(titleFilter.isRelevant(crypto("이더리움"), CRYPTO_NAMES, title)).isFalse();
	}

	// 실측(2026-08-07)에서 실제로 통과하던 제목들이다. 지어낸 예로만 단정하면 "우리가 상상한 오탐"만 막힌다.
	// 셋 다 질의어에 걸려 딸려 온 무관 기사이고, 개정 전에는 카드 근거와 LLM 입력에 그대로 들어갔다.
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

	// 목록이 비면 접두 관계를 알 방법이 없어 보호가 사라진다 — "비트코인캐시" 안의 "비트코인"이 독립 등장으로
	// 보인다. 개정 전후로 결과는 같지만 이유가 바뀌었다(전: 제외할 다른 이름이 없어서 / 후: 자기 이름이 보여서).
	// 호출부가 항상 같은 시장 목록을 넘기므로 운영에서는 이 경로가 없다.
	@Test
	@DisplayName("같은 시장 종목명 목록이 비면 접두 보호가 사라진다 — 호출부가 목록을 넘기는 것이 전제다")
	void losesPrefixProtectionWhenSameMarketNamesIsEmpty() {
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
