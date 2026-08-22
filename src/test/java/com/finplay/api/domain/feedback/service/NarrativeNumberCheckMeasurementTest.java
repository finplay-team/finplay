// spec 053 §측정 ①② — 숫자 환각 적발률과 참인 문장 차단율을 재는 하네스이자, 구현 전 기존 검증기의 대조군.
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

/**
 * <b>구현 전에 먼저 만든다.</b> spec 053 §측정 ①의 값은 "새 검증기가 더 잡는다"가 아니라
 * <b>"기존 검증기가 0건이었다"</b>에 있는데, 구현한 뒤에 재면 그 0건을 다시 만들어 낼 수 없다.
 * 그래서 이 클래스는 FEED-014 구현보다 앞선 커밋에 들어간다.
 *
 * <p><b>기존 검증기의 목록을 여기에 얼려 둔다</b>({@link #LEGACY_RULES}). {@code NarrativeValidator}를 직접
 * 부르지 않는 이유는 FEED-014가 그 클래스에 숫자 대조를 더할 예정이기 때문이다 — 운영 클래스를 부르면
 * 구현 커밋에서 ①의 <b>0/12가 함께 움직여 비교 자체가 사라진다.</b> 얼린 목록은 2026-08-22 시점의
 * {@code 012} §후검증 표 5줄 37개 그대로이며, 지금 운영 검증기와도 같은 판정을 낸다는 것을
 * {@link #todaysValidatorReproducesTheFrozenBaseline()}이 함께 확인한다.
 *
 * <p><b>그 걱정은 결과적으로 기우였다 — 얼려 두길 잘한 것과는 별개다.</b> 숫자 대조는 {@code NarrativeValidator}
 * 안이 아니라 별도 클래스({@link NarrativeNumberValidator})로 갔고(053 plan §결정 A), 그래서 운영 검증기의
 * 판정이 무변경이라 ①의 0/12가 운영 클래스에서도 그대로 재현된다. 얼린 목록은 <b>그대로 둔다</b> — 대조군을
 * 구현과 무관하게 고정해 두는 것이 이 하네스의 값이기 때문이다.
 *
 * <p><b>②는 고쳐야 할 결함이 아니다.</b> 반사실을 서술에서 뺀 것은 {@code 012} §왜 반사실은 AI 문장에 넣지
 * 않는가의 <b>제품 판단</b>이고, 6/6 차단은 그 판단이 코드에서 지켜지고 있다는 뜻이다. 반사실 해금은 이
 * spec에서 분리했으므로 <b>구현 후에도 6/6이 유지되는 것이 정상</b>이다 (2026-08-23 범위 축소).
 * 게다가 이 여섯 문장은 <b>운영에서 발생한 적이 없다</b> — 반사실이 프롬프트에 들어간 적이 없어 모델이 쓸
 * 재료를 받은 적이 없는, 순수한 합성 입력이다. ①의 열두 건은 운영에서 실제로 나올 수 있어 성격이 다르다.
 *
 * <p><b>수치는 전부 문서에서 왔다.</b> 프롬프트 고정값은 {@code 012} §LLM 프롬프트의 매도 회고 예시(삼성전자
 * 09:30 매수 → 14:40 매도)이고, 반사실 두 값(-1.17%·+1.11%)은 같은 spec §왜 반사실은 AI 문장에 넣지 않는가의
 * 표에 적힌 값이다. 지어낸 수치가 하나도 없어야 발표에서 이 표를 근거로 쓸 수 있다.
 *
 * <p><b>LLM을 부르지 않는다.</b> ①②는 서술을 고정 문자열로 주입하는 측정이라 실호출이 없다(§측정 전에 확인할
 * 것). 실호출이 필요한 ③④는 이 클래스에 넣지 않는다.
 */
class NarrativeNumberCheckMeasurementTest {

	// ---------- 얼린 대조군: 2026-08-22 시점의 §후검증 표 5줄 37개 ----------

	private static final List<String> CAUSATION = List.of("때문에", "영향으로", "여파로", "덕분에", "로 인해", "탓에");

	private static final List<String> RECOMMENDATION = List.of("매수하세요", "매도하세요", "사야", "팔아야", "추천", "주목할", "유망",
		"비중 확대");

	private static final List<String> PREDICTION = List.of("오를 것", "내릴 것", "전망", "예상됩니다", "기대됩니다", "상승할 것", "하락할 것");

	private static final List<String> ADVICE = List.of("하세요", "했으면", "좋았을", "아쉽", "권장");

	private static final List<String> JUDGEMENT = List.of("버티", "놓치", "실수", "잘못", "다행", "기회를", "았다면", "었다면", "였다면",
		"했다면", "렸다면");

	private static final List<String> LEGACY_RULES = Stream
		.of(CAUSATION, RECOMMENDATION, PREDICTION, ADVICE, JUDGEMENT).flatMap(List::stream).toList();

	// ---------- 프롬프트 고정값: 012 §LLM 프롬프트의 매도 회고 예시 ----------

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);

	// ---------- ① 숫자 환각 12건 (자릿수 3 · 부호 3 · 반올림 3 · 출처 없음 3) ----------

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

	// ---------- ② 서버가 계산한 참인 반사실 6건 (가정법 어미 다섯을 모두 덮는다) ----------

	private static final List<String> COUNTERFACTUALS = List.of(
		"마감까지 보유했다면 수익률은 -1.17%였습니다.",
		"보유 중 최고가에 팔았다면 +1.11%였습니다.",
		"매도하지 않고 그대로 두었다면 -1.17%였습니다.",
		"같은 수량을 69,200원에 매도하였다면 -1.17%였습니다.",
		"매도를 미루고 마감까지 기다렸다면 -1.17%였습니다.",
		"11:05의 70,800원에 매도했다면 +1.11%였습니다.");

	private final NarrativePromptBuilder builder = new NarrativePromptBuilder();

	private final NarrativeValidator validator = new NarrativeValidator();

	// FEED-014로 더해진 새 축. 표현 축과 <b>별도 클래스</b>라 위 validator의 판정은 이 축이 생겨도 그대로다 —
	// 그것이 ①의 0/12 대조군이 구현 후에도 살아 있는 이유다 (053 plan §결정 A).
	private final NarrativeNumberValidator numberValidator = new NarrativeNumberValidator();

	static Stream<HallucinationCase> hallucinations() {
		return HALLUCINATIONS.stream();
	}

	static Stream<String> counterfactuals() {
		return COUNTERFACTUALS.stream();
	}

	// ---------- 하네스의 전제 ----------

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

		// 틀린 값이 프롬프트에 이미 있으면 그것은 환각이 아니라 정상 인용이라, 새 검증기도 잡지 않는 것이 맞다.
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

		// 모델은 이 두 수치를 받은 적이 없다. 그래서 ②의 여섯 문장은 운영에서 나온 적이 없는 합성 입력이고,
		// 새 검증기 기준으로도 "출처 없는 숫자"라 6/6 차단이 그대로 유지된다.
		assertThat(prompt).doesNotContain("-1.17%").doesNotContain("+1.11%");
		// 반대로 두 시나리오의 가격은 이미 프롬프트에 있다 — 없는 것은 "그때 팔았다면 얼마"라는 수익률뿐이다.
		assertThat(prompt).contains("69,200원").contains("70,800원");
	}

	// ---------- 측정 ① 숫자 환각 적발률 ----------

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
			.as("적발돼야 한다(틀린 값 %s는 프롬프트에 없다): %s", testCase.wrong(), testCase.narrative())
			.isNotEmpty();
	}

	// ---------- 측정 ② 참인 문장 차단율 ----------

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

		// 표현 축은 무변경이다 — 이 spec은 ②의 6/6을 <b>바꾸지 않는 것이 정상</b>이다 (2026-08-23 범위 축소).
		assertThat(validator.validateCardOrPostSell(narrative).detectedExpressions())
			.as("가정법 어미에 계속 걸려야 한다: %s", narrative)
			.isNotEmpty();
		// 숫자 축도 독립적으로 잡는다 — 반사실 두 값(-1.17%·+1.11%)이 프롬프트에 없기 때문이다.
		assertThat(numberValidator.validate(narrative, prompt).detectedExpressions())
			.as("반사실 수익률이 출처 없는 수치여야 한다: %s", narrative)
			.isNotEmpty();
	}

	// ---------- 얼린 목록이 오늘의 운영 검증기와 같은 판정을 낸다 ----------

	/**
	 * <b>이 테스트는 이제 FEED-016의 증거다</b> — 숫자 대조를 더하면서 표현 축이 한 글자도 움직이지
	 * 않았다는 것을, 2026-08-22에 얼린 목록과 오늘의 운영 검증기가 같은 판정을 낸다는 사실로 보인다.
	 *
	 * <p><b>구현 전의 예고("FEED-014가 숫자 대조를 더하면 첫 번째 단정이 깨진다")는 빗나갔다.</b> 그 예고는
	 * 숫자 대조가 {@code NarrativeValidator} 안으로 들어간다는 전제 위에 있었는데, 실제로는 별도 클래스
	 * ({@link NarrativeNumberValidator})로 뺐다 (053 plan §결정 A). {@code validateCardOrPostSell}의 판정이
	 * 무변경이라 <b>①의 0/12 대조군이 구현 후에도 운영 클래스에서 그대로 재현된다.</b>
	 *
	 * <p>새 검증기 쪽 12/12는 {@link #numberValidatorDetectsEveryHallucination}이 따로 잰다. 얼린 목록
	 * ({@link #LEGACY_RULES})은 그대로 둔다 — ①의 0/12 대조군이 거기 있다.
	 */
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

	// ---------- 발표용 표 ----------

	@Test
	@DisplayName("측정 결과표를 출력한다 — spec §측정 ①②의 두 열(기존·새 검증기)을 함께 채운다")
	void printsTheMeasurementTable() {
		String prompt = builder.postSellPrompt(specPostSell());

		long legacyDetected = HALLUCINATIONS.stream().filter(c -> !legacyDetect(c.narrative()).isEmpty()).count();
		long legacyBlocked = COUNTERFACTUALS.stream().filter(n -> !legacyDetect(n).isEmpty()).count();
		long numberDetected = HALLUCINATIONS.stream()
			.filter(c -> !numberValidator.validate(c.narrative(), prompt).detectedExpressions().isEmpty())
			.count();
		// ②의 새 검증기 열은 운영 경로 그대로 두 축을 합쳐 센다 — 매도 회고는 표현 축과 숫자 축을 함께 받는다.
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

	// NarrativeService가 매도 회고에 적용하는 순서 그대로다 — 표현이 앞, 숫자가 뒤.
	private List<String> bothAxesDetect(String narrative, String prompt) {
		List<String> detected = new ArrayList<>(validator.validateCardOrPostSell(narrative).detectedExpressions());
		detected.addAll(numberValidator.validate(narrative, prompt).detectedExpressions());
		return detected;
	}

	// 2026-08-22 시점 NarrativeValidator.detect와 같은 구현이다 — 부분 문자열 대조뿐이고 숫자 규칙이 없다.
	private static List<String> legacyDetect(String narrative) {
		List<String> detected = new ArrayList<>();
		for (String expression : LEGACY_RULES) {
			if (narrative.contains(expression)) {
				detected.add(expression);
			}
		}
		return detected;
	}

	// 012 §LLM 프롬프트의 매도 회고 예시 그대로다. NarrativePromptBuilderTest의 골든 마스터가 같은 값을 쓴다.
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

	/** 주입 케이스 1건 — 어떤 갈래로 어떤 값을 어떻게 틀렸는지까지 담아 실패 메시지에서 바로 읽히게 한다. */
	private record HallucinationCase(String kind, String narrative, String wrong, String actual) {

		@Override
		public String toString() {
			return "%s: %s → %s".formatted(kind, actual, wrong);
		}
	}
}
