// NarrativeValidator의 금지 표현 37개와 파트별 비대칭 적용이 spec §후검증 표와 일치하는지 검증하는 단위 테스트.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.entity.NarrativeSource;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 아래 다섯 목록은 spec §후검증 표를 <b>그대로 옮긴 것</b>이며 구현의 private 목록을 읽어 온 것이 아니다.
 * 이 중복이 곧 검증 수단이다 — 구현이 표에서 하나라도 빠지거나 늘어나면 여기서 갈린다.
 *
 * <p>통과·차단의 경계 자체가 완료 조건이라(§완료 조건 §문구) 목록을 넓히지도 좁히지도 않는다.
 * 어간으로 일반화하면(`매도하(세요)?`) "매도했습니다 통과"가 곧바로 깨진다.
 */
class NarrativeValidatorTest {

	// spec §후검증 표의 다섯 줄. 줄 순서·줄 안의 표현 순서까지 표 그대로다 — 적발 목록의 순서가 이 순서를 따른다.
	private static final List<String> CAUSATION = List.of("때문에", "영향으로", "여파로", "덕분에", "로 인해", "탓에");

	private static final List<String> RECOMMENDATION = List.of("매수하세요", "매도하세요", "사야", "팔아야", "추천", "주목할", "유망",
		"비중 확대");

	private static final List<String> PREDICTION = List.of("오를 것", "내릴 것", "전망", "예상됩니다", "기대됩니다", "상승할 것", "하락할 것");

	private static final List<String> ADVICE = List.of("하세요", "했으면", "좋았을", "아쉽", "권장");

	private static final List<String> JUDGEMENT = List.of("버티", "놓치", "실수", "잘못", "다행", "기회를", "았다면", "었다면", "였다면",
		"했다면", "렸다면");

	// 요약·브리핑에 적용하는 4줄 (26개)과 카드·매도 회고에 적용하는 5줄 (37개).
	private static final List<String> FOUR_RULES = Stream.of(CAUSATION, RECOMMENDATION, PREDICTION, ADVICE)
		.flatMap(List::stream).toList();

	private static final List<String> FIVE_RULES = Stream.of(FOUR_RULES, JUDGEMENT).flatMap(List::stream).toList();

	// 금지 표현이 하나도 없는 운반 문장. 표현을 이 문장에 끼워 넣어 한 개씩 격리 검사한다.
	private static final String CARRIER_PREFIX = "이 서술에는 ";

	private static final String CARRIER_SUFFIX = " 라는 대목이 있습니다.";

	private final NarrativeValidator validator = new NarrativeValidator();

	static Stream<Arguments> everyForbiddenExpression() {
		return Stream.concat(
			FOUR_RULES.stream().map(expression -> Arguments.of(expression, false)),
			JUDGEMENT.stream().map(expression -> Arguments.of(expression, true)));
	}

	// ---------- 확인 1: 37개 전수 대조 ----------

	@Test
	@DisplayName("spec §후검증 표가 5줄 37개이고 줄별 개수가 6·8·7·5·11이다")
	void specTableHasThirtySevenExpressions() {
		assertThat(CAUSATION).hasSize(6);
		assertThat(RECOMMENDATION).hasSize(8);
		assertThat(PREDICTION).hasSize(7);
		assertThat(ADVICE).hasSize(5);
		// 가정법 어미가 셋에서 다섯으로 늘었다 — `했다면`·`렸다면`이 빠져 있어 spec 자신의 예시를
		// 못 잡던 모순을 메웠다 (spec §후검증, 2026-08-03).
		assertThat(JUDGEMENT).hasSize(11);
		assertThat(FIVE_RULES).hasSize(37).doesNotHaveDuplicates();
		assertThat(FOUR_RULES).hasSize(26);
	}

	@Test
	@DisplayName("37개를 전부 담은 서술을 카드로 검증하면 표 순서 그대로 37개가 적발된다")
	void cardDetectsAllThirtySevenExpressionsInTableOrder() {
		String narrative = String.join(" ", FIVE_RULES);

		NarrativeValidationDto result = validator.validateCardOrPostSell(narrative);

		// 하나가 빠지면 개수에서, 다른 것으로 바뀌면 내용에서, 목록 순서가 흐트러지면 순서에서 갈린다.
		assertThat(result.detectedExpressions()).containsExactlyElementsOf(FIVE_RULES);
		assertThat(result.passed()).isFalse();
	}

	@Test
	@DisplayName("같은 서술을 요약으로 검증하면 판단·훈수 11개가 빠진 26개만 적발된다")
	void summaryDetectsOnlyTheTwentySixExpressionsInTableOrder() {
		String narrative = String.join(" ", FIVE_RULES);

		NarrativeValidationDto result = validator.validateSummaryOrBriefing(narrative);

		assertThat(result.detectedExpressions()).containsExactlyElementsOf(FOUR_RULES);
		assertThat(result.detectedExpressions()).doesNotContainAnyElementsOf(JUDGEMENT);
	}

	@ParameterizedTest(name = "[{0}] 판단·훈수={1}")
	@MethodSource("everyForbiddenExpression")
	@DisplayName("37개 표현이 하나씩 격리 검사에서 카드에 전부 적발된다")
	void cardDetectsEveryExpressionInIsolation(String expression, boolean judgement) {
		String narrative = CARRIER_PREFIX + expression + CARRIER_SUFFIX;

		NarrativeValidationDto result = validator.validateCardOrPostSell(narrative);

		assertThat(result.detectedExpressions()).contains(expression);
		assertThat(result.passed()).isFalse();
	}

	// ---------- 확인 2: 파트별 비대칭 ----------

	@ParameterizedTest(name = "[{0}] 판단·훈수={1}")
	@MethodSource("everyForbiddenExpression")
	@DisplayName("판단·훈수 11개만 요약에서 통과하고 나머지 26개는 요약에서도 적발된다")
	void summaryAppliesFourRulesOnly(String expression, boolean judgement) {
		String narrative = CARRIER_PREFIX + expression + CARRIER_SUFFIX;

		NarrativeValidationDto result = validator.validateSummaryOrBriefing(narrative);

		if (judgement) {
			// 요약에는 템플릿이 없어 걸리면 기능이 통째로 사라진다 — 그래서 이 11개는 요약에서 통과해야 한다.
			assertThat(result.passed()).as("요약에서 통과해야 한다: %s", expression).isTrue();
		} else {
			assertThat(result.detectedExpressions()).as("요약에서도 적발돼야 한다: %s", expression).contains(expression);
		}
	}

	@Test
	@DisplayName("운반 문장 자체에는 금지 표현이 없어 양쪽 다 통과한다 — 위 격리 검사의 전제다")
	void carrierSentenceItselfPassesBothParts() {
		String carrier = CARRIER_PREFIX + "관찰한 사실" + CARRIER_SUFFIX;

		assertThat(validator.validateCardOrPostSell(carrier).passed()).isTrue();
		assertThat(validator.validateSummaryOrBriefing(carrier).passed()).isTrue();
	}

	// ---------- 확인 3: 적발 목록의 내용과 순서 ----------

	@Test
	@DisplayName("여러 표현이 걸리면 등장 순서가 아니라 §후검증 표 순서로 담긴다")
	void detectedExpressionsFollowTableOrderNotAppearanceOrder() {
		// 등장 순서는 버티 → 기대됩니다 → 때문에 (표 순서의 역순)다.
		String narrative = "버티는 모습이었습니다. 기대됩니다. 실적 때문에 움직였습니다.";

		NarrativeValidationDto result = validator.validateCardOrPostSell(narrative);

		assertThat(result.detectedExpressions()).containsExactly("때문에", "기대됩니다", "버티");
	}

	@Test
	@DisplayName("같은 입력을 반복 검증해도 적발 목록이 항상 같다 — 재생성 프롬프트가 재현 가능해야 한다")
	void detectionIsDeterministicAcrossRepeatedCalls() {
		String narrative = "여파로 상승할 것으로 보이니 주목할 종목입니다.";

		List<String> first = validator.validateCardOrPostSell(narrative).detectedExpressions();
		List<String> second = validator.validateCardOrPostSell(narrative).detectedExpressions();
		List<String> third = validator.validateCardOrPostSell(narrative).detectedExpressions();

		assertThat(first).containsExactly("여파로", "주목할", "상승할 것");
		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
	}

	@Test
	@DisplayName("한 표현이 다른 표현을 품으면 둘 다 담긴다 — 매수하세요는 하세요도 함께 적발된다")
	void nestedExpressionsAreBothReported() {
		NarrativeValidationDto result = validator.validateSummaryOrBriefing("지금 매수하세요.");

		// 6번의 재생성 프롬프트가 이 목록을 그대로 쓴다. 하나로 뭉뚱그리면 모델에게 주는 정보가 줄어든다.
		assertThat(result.detectedExpressions()).containsExactly("매수하세요", "하세요");
	}

	@Test
	@DisplayName("적발 목록은 밖에서 바꿀 수 없는 복사본이다")
	void detectedExpressionsAreImmutable() {
		NarrativeValidationDto result = validator.validateCardOrPostSell("전망이 밝습니다.");

		assertThat(result.detectedExpressions()).isUnmodifiable();
	}

	// ---------- 확인 4: §완료 조건 §문구 1~5번째 ----------

	@Test
	@DisplayName("문구 1 — 금지 표현 5줄이 §후검증 파트별 적용 표대로 동작한다")
	void condition1PartTableIsApplied() {
		String judgementOnly = "그 뒤에도 계속 버티는 흐름이었습니다.";

		// 카드·매도 회고는 5줄 전부, 요약·브리핑은 판단·훈수를 뺀 4줄.
		assertThat(validator.validateCardOrPostSell(judgementOnly).detectedExpressions()).containsExactly("버티");
		assertThat(validator.validateSummaryOrBriefing(judgementOnly).passed()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"버티", "놓치", "았다면"})
	@DisplayName("문구 2 — 판단·훈수 표현(버티·놓치·았다면)이 매도 회고에서 적발돼 템플릿으로 대체된다")
	void condition2JudgementExpressionsAreCaughtInPostSell(String expression) {
		String narrative = "매도 시점을 " + expression + " 관련 대목이 있습니다.";

		NarrativeValidationDto result = validator.validateCardOrPostSell(narrative);

		assertThat(result.passed()).isFalse();
		assertThat(result.detectedExpressions()).contains(expression);
	}

	@Test
	@DisplayName("문구 3 — 요약에 \"기회를\"이 들어가도 통과한다 (판단·훈수 미적용)")
	void condition3OpportunityPassesInSummary() {
		String narrative = "여러 기업이 신규 사업 기회를 언급한 기사들이 있었습니다.";

		assertThat(validator.validateSummaryOrBriefing(narrative).passed()).isTrue();
		// 같은 문장이 카드에서는 걸린다 — 이 차이가 이 항목의 핵심이다.
		assertThat(validator.validateCardOrPostSell(narrative).detectedExpressions()).containsExactly("기회를");
	}

	@Test
	@DisplayName("문구 4 — 요약에 \"하세요\"가 들어가면 걸린다 (조언·후회는 요약에서도 유지)")
	void condition4AdviceIsStillBlockedInSummary() {
		String narrative = "관련 공시를 확인하세요.";

		NarrativeValidationDto result = validator.validateSummaryOrBriefing(narrative);

		// C-004의 "조언하지 않는다"가 요약에서 사라지면 안 된다.
		assertThat(result.passed()).isFalse();
		assertThat(result.detectedExpressions()).containsExactly("하세요");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"70,000원에 매수해 68,500원에 매도했습니다.",
		"보유 중 최고가는 11:05의 70,800원이었습니다.",
		"09:32부터 5분간 2.10% 상승했습니다. 같은 시간대에 기사 2건이 있었습니다."})
	@DisplayName("문구 5 — \"매도했습니다\" 같은 서술형과 템플릿 문장은 양쪽 파트에서 모두 통과한다")
	void condition5PlainNarrationPasses(String narrative) {
		// 템플릿 문장이 후검증에 걸리면 폴백 자체가 성립하지 않는다 (§템플릿 문장).
		assertThat(validator.validateCardOrPostSell(narrative).passed()).isTrue();
		assertThat(validator.validateSummaryOrBriefing(narrative).passed()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"매도했습니다", "매수했습니다", "매도하기로 했습니다", "매수한 뒤였습니다"})
	@DisplayName("어간으로 일반화하면 깨지는 서술형들이 전부 통과한다 — 목록에 매도하세요만 있고 매도하는 없다")
	void verbStemsAreNotGeneralized(String narration) {
		assertThat(validator.validateCardOrPostSell(narration).passed()).isTrue();
	}

	// ---------- 목록을 넓히지 않았는지 (근접 표현은 통과) ----------

	@ParameterizedTest
	@ValueSource(strings = {
		"주가가 오를까 지켜본 기사였습니다.",
		"시장의 기대감을 다룬 기사였습니다.",
		"예상보다 이른 시점이었습니다.",
		"새 기회가 있다는 기사였습니다.",
		"거래량에 주목했습니다.",
		"영향력이 큰 기업입니다."})
	@DisplayName("목록에 없는 근접 표현은 통과한다 — 어간·유사어로 넓히면 여기서 깨진다")
	void nearMissExpressionsPass(String narrative) {
		assertThat(validator.validateCardOrPostSell(narrative).passed()).isTrue();
	}

	// ---------- 가정법 어미: spec 모순을 메운 뒤의 동작 ----------

	@ParameterizedTest
	@ValueSource(strings = {"더 기다렸다면 달랐을 수 있습니다", "그대로 보유했다면 어땠을지 모릅니다"})
	@DisplayName("spec 자신의 예시인 \"기다렸다면\"·\"보유했다면\"이 이제 매도 회고에서 적발된다")
	void previouslyEscapingConditionalEndingsAreNowCaught(String narrative) {
		// 이 테스트는 원래 "지금은 통과한다"를 문서화하던 자리였다. spec §후검증 목록에 `했다면`·`렸다면`을
		// 추가하면서 뒤집었다 — 목록에 `았다면`·`었다면`·`였다면`만 있던 동안은 초성이 달라(렸·했) 부분
		// 문자열로 겹치지 않아 빠져나갔고, spec 본문과 api-contracts 문구 제약이 둘 다 "더 기다렸다면"을
		// 막아야 할 대표 예로 드는 것과 어긋났다.
		assertThat(validator.validateCardOrPostSell(narrative).passed()).isFalse();

		// 요약·브리핑에는 판단·훈수를 적용하지 않으므로 여전히 통과한다 — 비대칭은 그대로다.
		assertThat(validator.validateSummaryOrBriefing(narrative).passed()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"그때 팔았다면 달랐습니다", "조금 더 먹었다면 좋습니다", "그대로 하였다면 달랐습니다"})
	@DisplayName("원래 목록에 있던 세 어미(았다면·었다면·였다면)도 그대로 잡힌다 — 추가가 기존 동작을 바꾸지 않았다")
	void listedConditionalEndingsAreCaught(String narrative) {
		assertThat(validator.validateCardOrPostSell(narrative).passed()).isFalse();
		assertThat(validator.validateSummaryOrBriefing(narrative).passed()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"매수했다고 합니다", "그대로 보유했습니다", "기다렸습니다", "하락했다는 기사였습니다"})
	@DisplayName("`했다면`·`렸다면` 추가가 서술형 과거시제까지 넓히지는 않는다 — 어미가 `~다면`일 때만 걸린다")
	void addedConditionalEndingsDoNotCatchPlainPastTense(String narration) {
		assertThat(validator.validateCardOrPostSell(narration).passed()).isTrue();
	}

	// ---------- 빈 서술과 열거형 ----------

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "\n\t "})
	@DisplayName("서술이 없거나 공백뿐이면 위반이 아니다 — 생성 실패는 호출부가 먼저 가린다")
	void blankNarrativeIsNotAViolation(String narrative) {
		assertThat(validator.validateCardOrPostSell(narrative).passed()).isTrue();
		assertThat(validator.validateSummaryOrBriefing(narrative).passed()).isTrue();
	}

	@Test
	@DisplayName("NarrativeSource는 §C-4 표기 그대로 LLM·TEMPLATE·NONE 셋이다")
	void narrativeSourceKeepsSpecNames() {
		// #2가 @Enumerated(STRING)으로 재사용하므로 이름을 바꾸면 저장된 문자열과 어긋난다 (§C-8).
		assertThat(NarrativeSource.values())
			.extracting(Enum::name)
			.containsExactly("LLM", "TEMPLATE", "NONE");
	}
}
