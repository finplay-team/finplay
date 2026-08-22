// NarrativeNumberValidator의 판정 규칙이 spec 053 §결정 1의 판정 표·§결정 C의 추출 규칙과 일치하는지 검증하는 단위 테스트.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 외부 의존이 없는 순수 계산이라 mock이 없다 (ADR-0003 — 이 축은 단위 테스트가 정본이다).
 *
 * <p>아래 {@link #SPEC_PROMPT}는 {@code NarrativePromptBuilderTest.postSellPromptMatchesSpecExample}의
 * 골든 마스터를 <b>그대로 옮긴 것</b>이며 {@code NarrativePromptBuilder}를 불러 만든 것이 아니다. 이
 * 중복이 곧 검증 수단이다 — 허용 집합이 "사용자 프롬프트 문자열 하나"에서만 나온다는 것을 조립기
 * 없이 보이고, spec §결정 1의 판정 표가 든 {@code -15,207원}·{@code -2.17%}가 실제로 그 프롬프트에서
 * 오는 값이라는 것도 함께 고정한다.
 *
 * <p><b>이 테스트는 대체 규칙을 보지 않는다.</b> 적발 후 무엇으로 대체할지는 {@code NarrativeService}가
 * 정하고 (FEED-017), 이 클래스는 판정만 한다.
 */
class NarrativeNumberValidatorTest {

	// NarrativePromptBuilderTest의 매도 회고 골든 마스터와 같은 문자열이다.
	//
	// 여기서 나오는 허용 집합(정규화 후) — 09·30·70,000·10·14·40·68,500·-2.17·-15,207·11·05·70,800·
	// 3.25·20·68,100·0.59·15·105·25·-1.82·115·195·69,200·1.02·3·4.
	private static final String SPEC_PROMPT = """
		종목: 삼성전자
		매수: 09:30, 70,000원 10
		매도: 14:40, 68,500원 10
		수익률: -2.17% (실현손익 -15,207원)

		보유 중 최고가: 11:05의 70,800원 (매도가가 3.25% 낮음)
		보유 중 최저가: 14:20의 68,100원 (매도가가 0.59% 높음)
		매수는 첫 근거 기사(11:15)보다 105분 앞섰습니다.

		보유 구간에 걸친 변동:
		- 11:20~11:25 -1.82% (매수 115분 뒤, 매도 195분 전)
		  근거: 삼성전자 반도체 공장 가동 일시 중단 (한국경제, 11:15)

		매도 후 흐름: 마감 종가 69,200원 (매도가보다 1.02% 높음)

		위 내용을 3~4문장으로 서술해줘. 수치를 그대로 나열하지 말고,
		매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘.""";

	private final NarrativeNumberValidator validator = new NarrativeNumberValidator();

	// ---------- 확인 1: spec §결정 1의 판정 표 6행 ----------

	// 표의 여섯 줄을 순서 그대로 옮긴다. 앞의 세 줄이 통과, 뒤의 세 줄이 위반이다.
	static Stream<Arguments> specDecisionTable() {
		return Stream.of(
			Arguments.of("15,207원 손실이었습니다.", true, "부호를 말로 옮긴 것뿐이다"),
			Arguments.of("-15,207원이었습니다.", true, "그대로다"),
			Arguments.of("2.17% 손실이었습니다.", true, "부호를 말로 옮긴 것뿐이다"),
			Arguments.of("-152,070원이었습니다.", false, "자릿수가 다르다"),
			Arguments.of("약 15,000원이었습니다.", false, "반올림은 허용하지 않는다"),
			Arguments.of("+15,207원이었습니다.", false, "부호 기호를 붙였는데 방향이 반대다"));
	}

	@ParameterizedTest(name = "[{index}] {0} → {2}")
	@MethodSource("specDecisionTable")
	@DisplayName("spec §결정 1의 판정 표 6행이 표대로 나온다 (통과 3 · 위반 3)")
	void specDecisionTableRowsJudgeAsTabulated(String narrative, boolean expectedPass, String reason) {
		NarrativeValidationDto result = validator.validate(narrative, SPEC_PROMPT);

		assertThat(result.passed()).as("%s — %s", narrative, reason).isEqualTo(expectedPass);
	}

	@Test
	@DisplayName("위반 3행의 적발 목록에 원문 토큰이 그대로 담긴다")
	void violatingRowsReportTheOriginalToken() {
		assertThat(validator.validate("-152,070원이었습니다.", SPEC_PROMPT).detectedExpressions())
			.containsExactly("-152,070");
		assertThat(validator.validate("약 15,000원이었습니다.", SPEC_PROMPT).detectedExpressions())
			.containsExactly("15,000");
		assertThat(validator.validate("+15,207원이었습니다.", SPEC_PROMPT).detectedExpressions())
			.containsExactly("+15,207");
	}

	// ---------- 확인 2: 추출 규칙 (plan §결정 C) ----------

	@Test
	@DisplayName("천 단위 구분자는 세 자리 묶음으로만 붙어 꼬리 쉼표를 삼키지 않는다")
	void thousandsSeparatorDoesNotSwallowTheTrailingComma() {
		// 정규식을 `[\d,]*`로 느슨하게 잡으면 `1,234, 567`이 한 토큰이 된다. 두 토큰으로 갈리는지를
		// 프롬프트에 없는 수로 보인다 — 적발 목록에 갈린 모양 그대로 남아야 한다.
		NarrativeValidationDto result = validator.validate("수치는 1,234, 567이었습니다.", SPEC_PROMPT);

		assertThat(result.detectedExpressions()).containsExactly("1,234", "567");
	}

	@Test
	@DisplayName("천 단위 구분자가 있든 없든 같은 값으로 본다")
	void thousandsSeparatorIsNormalizedAway() {
		assertThat(validator.validate("70,000원에 매수했습니다.", SPEC_PROMPT).passed()).isTrue();
		assertThat(validator.validate("70000원에 매수했습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("`%`·`원`·`분` 같은 단위와 조사는 대조하지 않는다 — 수만 본다")
	void unitsAndParticlesAreNotCompared() {
		// 프롬프트는 `-2.17%`·`-15,207원`·`105분`으로 주는데 서술이 단위를 바꿔 붙여도 수가 같으면 통과다.
		String narrative = "수익률 2.17분, 실현손익 15,207%, 간격 105원으로 적어도 수는 그대로입니다.";

		assertThat(validator.validate(narrative, SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("소수점은 뒤에 숫자가 있을 때만 붙어 문장 끝 마침표를 소수점으로 읽지 않는다")
	void sentenceEndingPeriodIsNotReadAsADecimalPoint() {
		// `77.`이 한 토큰이 되면 BigDecimal 생성에서 터진다. 마침표를 뗀 `77`만 남아야 한다.
		NarrativeValidationDto result = validator.validate("보유 수량은 77. 그대로였습니다.", SPEC_PROMPT);

		assertThat(result.detectedExpressions()).containsExactly("77");
	}

	@Test
	@DisplayName("소수 자릿수 표기가 달라도 같은 값으로 본다")
	void trailingZerosDoNotChangeTheValue() {
		// BigDecimal.equals는 scale까지 보므로 compareTo여야 `-2.17`과 `-2.170`이 같은 값이 된다.
		assertThat(validator.validate("-2.170%였습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	// ---------- 확인 3: 콜론 분리 (plan §결정 C — 깨지면 정상 서술이 통째로 템플릿으로 떨어진다) ----------

	@Test
	@DisplayName("프롬프트의 `09:30`은 09와 30 두 수로 들어와 \"09시 30분에 매수했습니다\"가 통과한다")
	void colonSplitsTimeIntoTwoNumbersSoNormalNarrativePasses() {
		// 시각을 하나로 묶으면 `0930`이 허용 집합에 들어가고 서술의 `09`·`30`은 둘 다 출처가 없어져
		// 정상 서술이 전부 위반이 된다 — 매도 회고가 통째로 템플릿으로 떨어지는 자리다.
		assertThat(validator.validate("09시 30분에 매수했습니다.", "매수: 09:30").passed()).isTrue();

		// 골든 마스터 프롬프트에서도 매수·매도 시각을 그대로 옮긴 정상 서술이 통과한다.
		String narrative = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
		assertThat(validator.validate(narrative, SPEC_PROMPT).passed()).isTrue();
	}

	/**
	 * <b>알려진 동작이며 버그가 아니다 — 정규식에 예외를 넣어 고치지 않는다.</b>
	 *
	 * <p>서술이 시각 범위를 하이픈으로 쓰면({@code 09:30-14:40}) {@code -14}가 음수 토큰으로 잡혀 위반이
	 * 된다. 고치려면 "앞이 숫자면 부호로 읽지 않는다" 같은 예외가 필요한데, 그 순간 plan §결정 C의
	 * <b>"토큰 정규식은 하나다"</b>가 깨지고 예외 목록이 자라기 시작한다 — 053이 벗어나려는
	 * "표현 목록으로 하는 검증"으로 되돌아가는 길이다.
	 *
	 * <p>고치지 않아도 되는 이유는 <b>프롬프트도 템플릿도 구간을 전부 {@code ~}로 쓰기</b> 때문이다
	 * ({@code NarrativePromptBuilder}의 {@code 11:20~11:25}). {@code ~}를 쓴 같은 문장이 통과하는 것을
	 * 아래에서 함께 못박아, 다음 사람이 이것을 오탐으로 오해하고 규칙을 더하지 않게 한다.
	 */
	@Test
	@DisplayName("서술이 시각 범위를 하이픈으로 쓰면 음수 토큰으로 잡혀 위반이다 (`~`는 통과 — 알려진 동작)")
	void hyphenatedTimeRangeIsReadAsANegativeNumber() {
		NarrativeValidationDto hyphen = validator.validate("09:30-14:40 동안 보유했습니다.", SPEC_PROMPT);

		assertThat(hyphen.passed()).isFalse();
		assertThat(hyphen.detectedExpressions()).containsExactly("-14");

		assertThat(validator.validate("09:30~14:40 동안 보유했습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	// ---------- 확인 4: 경계 ----------

	@ParameterizedTest
	@NullSource
	@EmptySource
	@ValueSource(strings = {" ", "\t", "\n"})
	@DisplayName("서술이 null·공백이면 위반이 아니다 — 생성 실패는 호출부가 먼저 가린다")
	void blankNarrativeIsNotAViolation(String narrative) {
		NarrativeValidationDto result = validator.validate(narrative, SPEC_PROMPT);

		assertThat(result.passed()).isTrue();
		assertThat(result.detectedExpressions()).isEmpty();
	}

	@Test
	@DisplayName("서술에 수치가 하나도 없으면 통과한다")
	void narrativeWithoutAnyNumberPasses() {
		assertThat(validator.validate("매수한 뒤 같은 날 매도했습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	@ParameterizedTest
	@NullSource
	@EmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("프롬프트가 비면 서술의 모든 수치가 위반이다")
	void emptyPromptMakesEveryNumberInTheNarrativeAViolation(String prompt) {
		NarrativeValidationDto result = validator.validate("09시 30분에 70,000원에 매수했습니다.", prompt);

		assertThat(result.detectedExpressions()).containsExactly("09", "30", "70,000");
	}

	// ---------- 확인 5: 적발 목록 (등장 순서 · 중복 제거 · 불변) ----------

	@Test
	@DisplayName("적발 목록은 등장 순서를 따르고 같은 토큰을 한 번만 담는다")
	void detectedTokensKeepAppearanceOrderWithoutDuplicates() {
		String narrative = "88은 77보다 크고 99보다 작으며 77이 다시 나옵니다.";

		NarrativeValidationDto result = validator.validate(narrative, SPEC_PROMPT);

		assertThat(result.detectedExpressions()).containsExactly("88", "77", "99");
	}

	@Test
	@DisplayName("중복 판정은 정규화 값이 아니라 토큰 글자로 한다 — `-77`과 `77`은 다른 토큰이다")
	void duplicateCheckUsesTheRawTokenNotTheNormalizedValue() {
		NarrativeValidationDto result = validator.validate("-77과 77은 다릅니다.", SPEC_PROMPT);

		assertThat(result.detectedExpressions()).containsExactly("-77", "77");
	}

	@Test
	@DisplayName("적발 목록은 불변이다")
	void detectedListIsImmutable() {
		List<String> detected = validator.validate("77이었습니다.", SPEC_PROMPT).detectedExpressions();

		assertThatThrownBy(() -> detected.add("88")).isInstanceOf(UnsupportedOperationException.class);
	}
}
