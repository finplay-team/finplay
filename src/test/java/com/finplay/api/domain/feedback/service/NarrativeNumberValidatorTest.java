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

class NarrativeNumberValidatorTest {

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

	@Test
	@DisplayName("천 단위 구분자는 세 자리 묶음으로만 붙어 꼬리 쉼표를 삼키지 않는다")
	void thousandsSeparatorDoesNotSwallowTheTrailingComma() {
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
		String narrative = "수익률 2.17분, 실현손익 15,207%, 간격 105원으로 적어도 수는 그대로입니다.";

		assertThat(validator.validate(narrative, SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("소수점은 뒤에 숫자가 있을 때만 붙어 문장 끝 마침표를 소수점으로 읽지 않는다")
	void sentenceEndingPeriodIsNotReadAsADecimalPoint() {
		NarrativeValidationDto result = validator.validate("보유 수량은 77. 그대로였습니다.", SPEC_PROMPT);

		assertThat(result.detectedExpressions()).containsExactly("77");
	}

	@Test
	@DisplayName("소수 자릿수 표기가 달라도 같은 값으로 본다")
	void trailingZerosDoNotChangeTheValue() {
		assertThat(validator.validate("-2.170%였습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("프롬프트의 `09:30`은 09와 30 두 수로 들어와 \"09시 30분에 매수했습니다\"가 통과한다")
	void colonSplitsTimeIntoTwoNumbersSoNormalNarrativePasses() {
		assertThat(validator.validate("09시 30분에 매수했습니다.", "매수: 09:30").passed()).isTrue();

		String narrative = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
		assertThat(validator.validate(narrative, SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("서술이 시각 범위를 하이픈으로 쓰면 음수 토큰으로 잡혀 위반이다 (`~`는 통과 — 알려진 동작)")
	void hyphenatedTimeRangeIsReadAsANegativeNumber() {
		NarrativeValidationDto hyphen = validator.validate("09:30-14:40 동안 보유했습니다.", SPEC_PROMPT);

		assertThat(hyphen.passed()).isFalse();
		assertThat(hyphen.detectedExpressions()).containsExactly("-14");

		assertThat(validator.validate("09:30~14:40 동안 보유했습니다.", SPEC_PROMPT).passed()).isTrue();
	}

	@Test
	@DisplayName("서로 다른 필드의 값을 맞바꿔 써도 통과한다 — 값 주머니에 위치 정보가 없다 (알려진 한계)")
	void swappedFieldValuesPassBecauseTheAllowedSetHasNoPositions() {
		assertThat(validator.validate("매수는 68,500원에, 매도는 70,000원에 이뤄졌습니다.", SPEC_PROMPT).passed())
			.isTrue();

		assertThat(validator.validate("매도가는 보유 중 최고가보다 0.59% 낮았습니다.", SPEC_PROMPT).passed())
			.isTrue();

		assertThat(validator.validate("매수는 68,400원에 이뤄졌습니다.", SPEC_PROMPT).detectedExpressions())
			.containsExactly("68,400");
	}

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
