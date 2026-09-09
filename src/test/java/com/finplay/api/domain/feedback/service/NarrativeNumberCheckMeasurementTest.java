package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NarrativeNumberCheckMeasurementTest {


	private static final List<String> CAUSATION = List.of("때문에", "영향으로", "여파로", "덕분에", "로 인해", "탓에");

	private static final List<String> RECOMMENDATION = List.of("매수하세요", "매도하세요", "사야", "팔아야", "추천", "주목할", "유망",
		"비중 확대");

	private static final List<String> PREDICTION = List.of("오를 것", "내릴 것", "전망", "예상됩니다", "기대됩니다", "상승할 것", "하락할 것");

	private static final List<String> ADVICE = List.of("하세요", "했으면", "좋았을", "아쉽", "권장");

	private static final List<String> JUDGEMENT = List.of("버티", "놓치", "실수", "잘못", "다행", "기회를", "았다면", "었다면", "였다면",
		"했다면", "렸다면");

	private static final String NUMBER_CHARS = "+-0123456789,.";

	private static final List<String> LEGACY_RULES = Stream
		.of(CAUSATION, RECOMMENDATION, PREDICTION, ADVICE, JUDGEMENT).flatMap(List::stream).toList();


	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);


	private static final List<HallucinationCase> HALLUCINATIONS = List.of(
		new HallucinationCase("자릿수", "수익률은 -21.7%였습니다.", "-21.7%", "-2.17%"),
		new HallucinationCase("자릿수", "실현손익은 -152,070원이었습니다.", "-152,070원", "-15,207원"),
		new HallucinationCase("자릿수", "매수는 첫 근거 기사보다 1,050분 앞섰습니다.", "1,050분", "105분"),
		new HallucinationCase("부호", "실현손익은 +15,207원이었습니다.", "+15,207원", "-15,207원"),
		new HallucinationCase("부호", "수익률은 +2.17%였습니다.", "+2.17%", "-2.17%"),
		new HallucinationCase("부호", "보유 구간에 걸친 변동은 +1.82%였습니다.", "+1.82%", "-1.82%"),
		new HallucinationCase("반올림", "실현손익은 약 15,000원이었습니다.", "15,000원", "-15,207원"),
		new HallucinationCase("반올림", "매수는 첫 근거 기사보다 110분 앞섰습니다.", "110분", "105분"),
		new HallucinationCase("반올림", "매도가는 68,000원이었습니다.", "68,000원", "68,500원"),
		new HallucinationCase("출처 없음", "20일 이동평균선은 69,500원이었습니다.", "69,500원", "프롬프트에 없음"),
		new HallucinationCase("출처 없음", "거래량은 직전 5거래일 평균의 2.4배였습니다.", "2.4배", "프롬프트에 없음"),
		new HallucinationCase("출처 없음", "장중 고점 대비 5.12% 낮은 가격에 매도했습니다.", "5.12%", "프롬프트에 없음"));


	private static final List<String> COUNTERFACTUALS = List.of(
		"마감까지 보유했다면 수익률은 -1.17%였습니다.",
		"보유 중 최고가에 팔았다면 +1.11%였습니다.",
		"매도하지 않고 그대로 두었다면 -1.17%였습니다.",
		"같은 수량을 69,200원에 매도하였다면 -1.17%였습니다.",
		"매도를 미루고 마감까지 기다렸다면 -1.17%였습니다.",
		"11:05의 70,800원에 매도했다면 +1.11%였습니다.");

	private final NarrativePromptBuilder builder = new NarrativePromptBuilder();

	private final NarrativeValidator validator = new NarrativeValidator();

	private final NarrativeNumberValidator numberValidator = new NarrativeNumberValidator();

	static Stream<HallucinationCase> hallucinations() {
		return HALLUCINATIONS.stream();
	}

	static Stream<String> counterfactuals() {
		return COUNTERFACTUALS.stream();
	}


	@Test
	@DisplayName("얼린 대조군이 §후검증 표 5줄 37개 그대로다 — 이 숫자가 흔들리면 ①②의 0건·6건이 무의미해진다")
	void frozenRulesAreTheSpecTableAsOf20260822() {
		assertThat(CAUSATION).hasSize(6);
		assertThat(RECOMMENDATION).hasSize(8);
		assertThat(PREDICTION).hasSize(7);
		assertThat(ADVICE).hasSize(5);
		assertThat(JUDGEMENT).hasSize(11);
		assertThat(LEGACY_RULES).hasSize(37).doesNotHaveDuplicates();
	}

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("hallucinations")
	@DisplayName("주입한 12건의 틀린 값은 프롬프트에 없고, 대신 실제 값이 프롬프트에 있다 — 측정 ①의 전제")
	void injectedNumbersAreAbsentFromThePromptButTheTrueOnesArePresent(HallucinationCase testCase) {
		String prompt = builder.postSellPrompt(specPostSell());

		assertThat(prompt).as("틀린 값이 프롬프트에 있으면 안 된다: %s", testCase.wrong())
			.doesNotContain(testCase.wrong());
		if (!"프롬프트에 없음".equals(testCase.actual())) {
			assertThat(prompt).as("실제 값은 프롬프트에 있어야 한다: %s", testCase.actual()).contains(testCase.actual());
		}
	}

	@Test
	@DisplayName("반사실 두 값(-1.17%·+1.11%)은 프롬프트에 없다 — ②의 6건이 합성 입력인 이유다")
	void counterfactualRatesAreNotInThePromptYet() {
		String prompt = builder.postSellPrompt(specPostSell());

		assertThat(prompt).doesNotContain("-1.17%").doesNotContain("+1.11%");
		assertThat(prompt).contains("69,200원").contains("70,800원");
	}


	@ParameterizedTest(name = "[{0}]")
	@MethodSource("hallucinations")
	@DisplayName("측정 ① — 기존 검증기는 숫자 환각 12건을 한 건도 적발하지 못한다")
	void legacyValidatorDetectsNoneOfTheHallucinations(HallucinationCase testCase) {
		assertThat(legacyDetect(testCase.narrative()))
			.as("기존 검증기가 적발하면 안 된다(표현 목록에 숫자 규칙이 없다): %s", testCase.narrative())
			.isEmpty();
	}

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("hallucinations")
	@DisplayName("측정 ① — 새 검증기는 숫자 환각 12건을 전량 적발한다 (기존 0 / 12 → 새 12 / 12)")
	void numberValidatorDetectsEveryHallucination(HallucinationCase testCase) {
		String prompt = builder.postSellPrompt(specPostSell());

		assertThat(numberValidator.validate(testCase.narrative(), prompt).detectedExpressions())
			.as("의도한 틀린 값 %s 때문에 적발돼야 한다: %s", testCase.wrong(), testCase.narrative())
			.contains(numberPartOf(testCase.wrong()));
	}


	@ParameterizedTest(name = "[{0}]")
	@MethodSource("counterfactuals")
	@DisplayName("측정 ② — 반사실을 문장으로 옮긴 6건은 전량 차단된다 (의도된 동작, 합성 입력)")
	void legacyValidatorBlocksEveryCounterfactual(String narrative) {
		assertThat(legacyDetect(narrative))
			.as("가정법 어미에 걸려야 한다: %s", narrative)
			.isNotEmpty();
	}

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("counterfactuals")
	@DisplayName("측정 ② — 새 축이 켜져도 6 / 6 그대로다. 표현 축이 여전히 잡고, 숫자 축도 따로 잡는다")
	void numberValidatorKeepsBlockingEveryCounterfactual(String narrative) {
		String prompt = builder.postSellPrompt(specPostSell());

		assertThat(validator.validateCardOrPostSell(narrative).detectedExpressions())
			.as("가정법 어미에 계속 걸려야 한다: %s", narrative)
			.isNotEmpty();
		assertThat(numberValidator.validate(narrative, prompt).detectedExpressions())
			.as("반사실 수익률이 출처 없는 수치여야 한다: %s", narrative)
			.isNotEmpty();
	}


	@Test
	@DisplayName("오늘의 NarrativeValidator도 얼린 대조군과 같은 판정을 낸다 — 표현 축이 한 글자도 안 움직였다 (FEED-016)")
	void todaysValidatorReproducesTheFrozenBaseline() {
		for (HallucinationCase testCase : HALLUCINATIONS) {
			assertThat(validator.validateCardOrPostSell(testCase.narrative()).detectedExpressions())
				.as("숫자 환각: %s", testCase.narrative())
				.isEqualTo(legacyDetect(testCase.narrative()));
		}
		for (String narrative : COUNTERFACTUALS) {
			assertThat(validator.validateCardOrPostSell(narrative).detectedExpressions())
				.as("반사실: %s", narrative)
				.isEqualTo(legacyDetect(narrative));
		}
	}


	@Test
	@DisplayName("측정 결과표를 출력한다 — spec §측정 ①②의 두 열(기존·새 검증기)을 함께 채운다")
	void printsTheMeasurementTable() {
		String prompt = builder.postSellPrompt(specPostSell());

		long legacyDetected = HALLUCINATIONS.stream().filter(c -> !legacyDetect(c.narrative()).isEmpty()).count();
		long legacyBlocked = COUNTERFACTUALS.stream().filter(n -> !legacyDetect(n).isEmpty()).count();
		long numberDetected = HALLUCINATIONS.stream()
			.filter(c -> !numberValidator.validate(c.narrative(), prompt).detectedExpressions().isEmpty())
			.count();
		long numberBlocked = COUNTERFACTUALS.stream().filter(n -> !bothAxesDetect(n, prompt).isEmpty()).count();

		StringBuilder report = new StringBuilder("\n[spec 053 측정 ①② — 기존 검증기 / 새 검증기]\n");
		report.append("① 숫자 환각 적발  : ").append(legacyDetected).append(" / ").append(HALLUCINATIONS.size())
			.append("   →   ").append(numberDetected).append(" / ").append(HALLUCINATIONS.size()).append('\n');
		for (HallucinationCase testCase : HALLUCINATIONS) {
			report.append("   적발 사유 ")
				.append(numberValidator.validate(testCase.narrative(), prompt).detectedExpressions())
				.append(" ← ").append(testCase.narrative()).append('\n');
		}
		report.append("② 가정법 문장 차단: ").append(legacyBlocked).append(" / ").append(COUNTERFACTUALS.size())
			.append("   →   ").append(numberBlocked).append(" / ").append(COUNTERFACTUALS.size())
			.append("  (변화 없음이 정상 — 의도된 동작이지 성과가 아니다)\n");
		for (String narrative : COUNTERFACTUALS) {
			report.append("   차단 사유 ").append(bothAxesDetect(narrative, prompt))
				.append(" ← ").append(narrative).append('\n');
		}
		System.out.println(report);

		assertThat(legacyDetected).isZero();
		assertThat(numberDetected).isEqualTo(HALLUCINATIONS.size());
		assertThat(legacyBlocked).isEqualTo(COUNTERFACTUALS.size());
		assertThat(numberBlocked).isEqualTo(COUNTERFACTUALS.size());
	}

	private List<String> bothAxesDetect(String narrative, String prompt) {
		List<String> detected = new ArrayList<>(validator.validateCardOrPostSell(narrative).detectedExpressions());
		detected.addAll(numberValidator.validate(narrative, prompt).detectedExpressions());
		return detected;
	}

	private static String numberPartOf(String wrong) {
		int end = 0;
		while (end < wrong.length() && NUMBER_CHARS.indexOf(wrong.charAt(end)) >= 0) {
			end++;
		}
		if (end == 0) {
			throw new IllegalArgumentException("틀린 값에 수가 없다: " + wrong);
		}
		return wrong.substring(0, end);
	}

	private static List<String> legacyDetect(String narrative) {
		List<String> detected = new ArrayList<>();
		for (String expression : LEGACY_RULES) {
			if (narrative.contains(expression)) {
				detected.add(expression);
			}
		}
		return detected;
	}

	private PostSellPromptDto specPostSell() {
		return new PostSellPromptDto(
			"삼성전자", TRADING_DATE.atTime(9, 30), bd("70000"), TRADING_DATE.atTime(14, 40), bd("68500"),
			bd("10.00"),
			bd("-0.0217"), -15207L, bd("70800"), TRADING_DATE.atTime(11, 5), bd("-0.0325"),
			bd("68100"), TRADING_DATE.atTime(14, 20), bd("0.0059"), 105, TRADING_DATE.atTime(11, 15),
			List.of(new HeldPriceMoveDto(
				TRADING_DATE.atTime(11, 20), TRADING_DATE.atTime(11, 25), bd("-0.0182"), 115, 195,
				List.of(new NewsSourceDto(
					"삼성전자 반도체 공장 가동 일시 중단", "한국경제", TRADING_DATE.atTime(11, 15), false)))),
			bd("69200"), bd("0.0102"), null, null, null, null, false, HoldHighBasis.MINUTE, List.of(), null);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private record HallucinationCase(String kind, String narrative, String wrong, String actual) {

		@Override
		public String toString() {
			return "%s: %s → %s".formatted(kind, actual, wrong);
		}
	}
}
