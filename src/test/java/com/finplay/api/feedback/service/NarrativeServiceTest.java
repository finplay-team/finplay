// NarrativeService의 1단계(템플릿 폴백)·2단계(재생성) 경로와 호출 횟수 상한을 검증하는 단위 테스트.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.feedback.config.FeedbackLlmProperties;
import com.finplay.api.feedback.domain.HoldHighBasis;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 생성기만 Fake로 바꾸고 프롬프트 조립·후검증·템플릿은 <b>실제 구현을 그대로 붙인다</b>. mock으로 다 막으면
 * "재생성 프롬프트에 적발 표현이 들어갔는가"(§완료 조건 문구 7번째)를 단정할 수 없고, 후검증 목록이 바뀔 때
 * 이 경로가 함께 흔들리는 것을 잡지 못한다 (ADR-0003: mock만으로 검증을 끝내지 않는다).
 *
 * <p>ADR-0011대로 실제 LLM은 호출하지 않는다 — {@code FakeNarrativeGenerator}가 응답을 시나리오로 고정한다.
 */
class NarrativeServiceTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);

	// 후검증을 통과하는 서술. 금지 표현이 하나도 없다.
	private static final String CLEAN_NARRATIVE = "09:32부터 5분간 2.10% 상승했습니다. 같은 시간대에 기사 2건이 있었습니다.";

	// 요약 4줄에 걸리는 서술. 경제 기사 제목의 `전망`을 그대로 옮긴 전형적인 실패다 (spec §후검증).
	private static final String SUMMARY_DIRTY = "업황 전망을 다룬 기사입니다. 실적 개선이 기대됩니다.";

	// 카드 5줄에만 있는 `판단·훈수`에 걸리는 서술 — 요약이었다면 통과했을 문장이다.
	private static final String CARD_DIRTY = "하락 이후에도 3시간이나 버티는 모습이었습니다.";

	private static final String CARD_TEMPLATE = "09:32부터 5분간 2.10% 상승했습니다. 같은 시간대에 기사 2건이 있었습니다.";

	private static final String POST_SELL_TEMPLATE = "70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다. 보유 중 최고가는 11:05의 70,800원이었습니다.";

	private static final String REGENERATION_MARKER = "직전 출력이 아래 금지 표현에 걸려 폐기됐다:";

	// ---------- ① 1차 적발 → 2차 통과 ----------

	@Test
	@DisplayName("① 요약이 1차에 걸려도 재생성 1회로 통과하면 LLM이 된다 — 기사 제목의 전망 때문에 기능이 사라지지 않는다")
	void summarySurvivesWhenRegenerationPasses() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue("반도체 업황을 다룬 기사들이 있었습니다.");
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(result.source()).isEqualTo(NarrativeSource.LLM);
		assertThat(result.narrative()).isEqualTo("반도체 업황을 다룬 기사들이 있었습니다.");
		assertThat(generator.callCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("① 브리핑도 같은 2단계 경로를 탄다")
	void briefingFollowsTheSameTwoStagePath() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue("여러 종목의 소식이 있었습니다.");
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolveMarketBriefingNarrative(briefing());

		assertThat(result.source()).isEqualTo(NarrativeSource.LLM);
		assertThat(generator.callCount()).isEqualTo(2);
	}

	// ---------- ② 2차도 적발되면 서술 없음 + NONE ----------

	@Test
	@DisplayName("② 재생성 후에도 걸리면 서술 없음 + NONE이다")
	void summaryEndsWithNoneWhenRegenerationAlsoFails() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue("여전히 전망을 다룬 기사입니다.");
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
		assertThat(result.narrative()).isNull();
		assertThat(result.hasNarrative()).isFalse();
		assertThat(generator.callCount()).isEqualTo(2);
	}

	// ---------- ③ 호출 횟수가 max-regeneration + 1을 넘지 않는다 (프로퍼티를 실제로 읽는가) ----------

	@ParameterizedTest(name = "max-regeneration={0} → 호출 {1}회")
	@CsvSource({"0, 1", "1, 2", "2, 3", "3, 4"})
	@DisplayName("③ 생성 호출 수가 max-regeneration + 1을 정확히 따라간다 — 상수로 박으면 여기서 갈린다")
	void generationCallCountFollowsTheMaxRegenerationProperty(int maxRegeneration, int expectedCalls) {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator();
		for (int i = 0; i < expectedCalls + 2; i++) {
			// 넉넉히 넣어 둔다 — 큐가 먼저 마르면 생성 실패로 빠져나가 횟수 검증이 무의미해진다.
			generator.enqueue(SUMMARY_DIRTY);
		}
		NarrativeService service = service(generator, maxRegeneration);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(generator.callCount()).isEqualTo(expectedCalls);
		assertThat(generator.callCount()).isLessThanOrEqualTo(maxRegeneration + 1);
		assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
	}

	@Test
	@DisplayName("③ max-regeneration=0이면 재생성 없이 곧바로 NONE이다 — 재생성 프롬프트도 만들지 않는다")
	void zeroMaxRegenerationSkipsRegenerationEntirely() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueue(SUMMARY_DIRTY).enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, 0);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(generator.callCount()).isEqualTo(1);
		assertThat(generator.userPrompts().get(0)).doesNotContain(REGENERATION_MARKER);
		assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
	}

	// ---------- 확인 2: 1단계에서 재생성이 절대 일어나지 않는다 ----------

	@ParameterizedTest(name = "max-regeneration={0}")
	@ValueSource(ints = {0, 1, 2, 5})
	@DisplayName("카드는 적발돼도 max-regeneration과 무관하게 호출이 항상 1회다")
	void priceMoveNeverRegeneratesRegardlessOfTheProperty(int maxRegeneration) {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(CARD_DIRTY)
			.enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, maxRegeneration);

		NarrativeResultDto result = service.resolvePriceMoveNarrative(priceMove());

		// 대체할 문장이 있으므로 재시도할 이유가 없다 (spec §후검증). 결과만 보면 정상이라 호출 수를 세야 잡힌다.
		assertThat(generator.callCount()).isEqualTo(1);
		assertThat(generator.userPrompts()).noneMatch(prompt -> prompt.contains(REGENERATION_MARKER));
		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
	}

	@ParameterizedTest(name = "max-regeneration={0}")
	@ValueSource(ints = {0, 1, 2, 5})
	@DisplayName("매도 회고도 적발돼도 호출이 항상 1회다")
	void postSellNeverRegeneratesRegardlessOfTheProperty(int maxRegeneration) {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(CARD_DIRTY)
			.enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, maxRegeneration);

		NarrativeResultDto result = service.resolvePostSellNarrative(postSell());

		assertThat(generator.callCount()).isEqualTo(1);
		assertThat(generator.userPrompts()).noneMatch(prompt -> prompt.contains(REGENERATION_MARKER));
		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
	}

	@Test
	@DisplayName("1단계가 통과하는 경우에도 호출은 1회다")
	void oneStagePathCallsGeneratorExactlyOnceOnSuccess() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, 3);

		NarrativeResultDto result = service.resolvePriceMoveNarrative(priceMove());

		assertThat(generator.callCount()).isEqualTo(1);
		assertThat(result.source()).isEqualTo(NarrativeSource.LLM);
		assertThat(result.narrative()).isEqualTo(CLEAN_NARRATIVE);
	}

	// ---------- ⑤ 카드·매도 회고는 적발 시 템플릿 문장 + TEMPLATE ----------

	@Test
	@DisplayName("⑤ 카드가 적발되면 §템플릿 문장의 장중 카드 문장으로 대체된다")
	void priceMoveFallsBackToTheSpecTemplateSentence() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueue(CARD_DIRTY);
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolvePriceMoveNarrative(priceMove());

		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(result.narrative()).isEqualTo(CARD_TEMPLATE);
	}

	@Test
	@DisplayName("⑤ 매도 회고가 적발되면 §템플릿 문장의 매도 회고 문장으로 대체된다")
	void postSellFallsBackToTheSpecTemplateSentence() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueue(CARD_DIRTY);
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolvePostSellNarrative(postSell());

		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(result.narrative()).isEqualTo(POST_SELL_TEMPLATE);
	}

	@Test
	@DisplayName("sameSessionCompleted=false인 매도 회고도 예외 없이 서술이 나온다 — 생성 성공·실패 양쪽 다")
	void postSellWithoutHoldExtremesResolvesWithoutException() {
		// 보유 구간 극값 6필드가 전부 null인 입력이다. 프롬프트 조립은 생성기 밖이라 여기서 NPE가 나면
		// Optional 폴백에 걸리지 않고 매도 회고 조회가 500이 된다 — "narrativeStatus는 항상 READY"(§C-4)가
		// 깨지는 자리다. 템플릿 폴백 경로와 LLM 성공 경로를 모두 확인한다.
		NarrativeResultDto generated = service(new FakeNarrativeGenerator().enqueue("정상 서술입니다."), 1)
			.resolvePostSellNarrative(multiSessionPostSell());

		assertThat(generated.source()).isEqualTo(NarrativeSource.LLM);
		assertThat(generated.narrative()).isEqualTo("정상 서술입니다.");

		NarrativeResultDto fallback = service(new FakeNarrativeGenerator().enqueueFailure(), 1)
			.resolvePostSellNarrative(multiSessionPostSell());

		// 극값이 없으면 템플릿도 셋째 문장을 빼고 두 문장만 만든다 (§템플릿 문장).
		assertThat(fallback.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(fallback.hasNarrative()).isTrue();
		assertThat(fallback.narrative()).doesNotContain("보유 중 최고가");
	}

	@Test
	@DisplayName("카드에서만 걸리는 판단·훈수 표현이 요약이었다면 통과한다 — 파트별 비대칭이 이 경로까지 이어진다")
	void judgementExpressionIsBlockedOnCardButAllowedOnSummary() {
		NarrativeResultDto card = service(new FakeNarrativeGenerator().enqueue(CARD_DIRTY), 1)
			.resolvePriceMoveNarrative(priceMove());
		NarrativeResultDto summary = service(new FakeNarrativeGenerator().enqueue(CARD_DIRTY), 1)
			.resolveNewsSummaryNarrative(newsSummary());

		assertThat(card.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(summary.source()).isEqualTo(NarrativeSource.LLM);
		assertThat(summary.narrative()).isEqualTo(CARD_DIRTY);
	}

	// ---------- ⑥ + 확인 3: 생성 실패와 후검증 적발이 같은 분기로 수렴 ----------

	@Test
	@DisplayName("⑥ 키가 없어 생성이 실패해도 카드는 템플릿으로 200 경로가 유지된다")
	void priceMoveKeepsTemplateWhenGenerationFails() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueueFailure();
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolvePriceMoveNarrative(priceMove());

		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(result.narrative()).isEqualTo(CARD_TEMPLATE);
		assertThat(generator.callCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("⑥ 매도 회고도 생성 실패 시 템플릿이라 서술이 비지 않는다 — narrativeStatus는 항상 READY다")
	void postSellKeepsTemplateWhenGenerationFails() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueueFailure();
		NarrativeService service = service(generator, 1);

		NarrativeResultDto result = service.resolvePostSellNarrative(postSell());

		assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(result.narrative()).isEqualTo(POST_SELL_TEMPLATE);
		assertThat(result.hasNarrative()).isTrue();
	}

	@Test
	@DisplayName("카드에서 생성 실패와 후검증 적발이 구별 불가능한 같은 결과가 된다")
	void generationFailureAndDetectionConvergeOnTheSameCardResult() {
		NarrativeResultDto afterFailure = service(new FakeNarrativeGenerator().enqueueFailure(), 1)
			.resolvePriceMoveNarrative(priceMove());
		NarrativeResultDto afterDetection = service(new FakeNarrativeGenerator().enqueue(CARD_DIRTY), 1)
			.resolvePriceMoveNarrative(priceMove());

		// 호출부가 두 경우를 구분할 수 있으면 §실패 처리 표의 "그 카드만 템플릿"이 두 갈래가 된다.
		assertThat(afterFailure).isEqualTo(afterDetection);
	}

	@ParameterizedTest(name = "max-regeneration={0}")
	@ValueSource(ints = {0, 1, 2, 5})
	@DisplayName("요약의 생성 호출 실패는 재생성 횟수를 소비하지 않고 곧바로 NONE이 된다")
	void summaryGenerationFailureDoesNotConsumeRegeneration(int maxRegeneration) {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueueFailure()
			.enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, maxRegeneration);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		// 키 없음·타임아웃은 다시 불러도 같은 이유로 실패한다 (spec §실패 처리, 2026-08-03 추가).
		assertThat(generator.callCount()).isEqualTo(1);
		assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
		assertThat(result.narrative()).isNull();
	}

	@Test
	@DisplayName("1차 적발 뒤 재생성 호출이 실패하면 거기서 멈추고 NONE이다")
	void summaryStopsWhenRegenerationCallItselfFails() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueueFailure()
			.enqueue(CLEAN_NARRATIVE);
		NarrativeService service = service(generator, 3);

		NarrativeResultDto result = service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(generator.callCount()).isEqualTo(2);
		assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
	}

	@Test
	@DisplayName("요약에서 생성 실패와 재생성 소진이 같은 NONE 결과로 수렴한다")
	void summaryFailureAndExhaustionConvergeOnNone() {
		NarrativeResultDto afterFailure = service(new FakeNarrativeGenerator().enqueueFailure(), 1)
			.resolveNewsSummaryNarrative(newsSummary());
		NarrativeResultDto afterExhaustion = service(
			new FakeNarrativeGenerator().enqueue(SUMMARY_DIRTY).enqueue(SUMMARY_DIRTY), 1)
			.resolveNewsSummaryNarrative(newsSummary());

		assertThat(afterFailure).isEqualTo(afterExhaustion);
		assertThat(afterFailure.source()).isEqualTo(NarrativeSource.NONE);
	}

	// ---------- ④ 재생성 프롬프트에 1차 적발 표현이 그대로 들어간다 ----------

	@Test
	@DisplayName("④ 2차 호출의 사용자 프롬프트에 1차 적발 표현이 전부 그대로 들어 있다")
	void regenerationPromptCarriesEveryDetectedExpression() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue("반도체 업황을 다룬 기사들이 있었습니다.");
		NarrativeService service = service(generator, 1);

		service.resolveNewsSummaryNarrative(newsSummary());

		String second = generator.userPrompts().get(1);
		assertThat(second).contains(REGENERATION_MARKER);
		// SUMMARY_DIRTY는 `전망`과 `기대됩니다` 둘에 걸린다 — 하나만 넘기면 2차도 남은 하나를 다시 쓴다.
		assertThat(second).contains("직전 출력이 아래 금지 표현에 걸려 폐기됐다: 전망, 기대됩니다");
		assertThat(second).doesNotContain("{적발된 표현들}");
	}

	@Test
	@DisplayName("④ 재생성 프롬프트가 1차 사용자 프롬프트를 통째로 유지한다 — 기사 목록이 빠지면 2차 재료가 없다")
	void regenerationPromptKeepsTheOriginalUserPrompt() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue("반도체 업황을 다룬 기사들이 있었습니다.");
		NarrativeService service = service(generator, 1);

		service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(generator.userPrompts().get(1)).startsWith(generator.userPrompts().get(0));
		assertThat(generator.systemPrompts()).containsExactly(
			new NarrativePromptBuilder().systemPrompt(), new NarrativePromptBuilder().systemPrompt());
	}

	@Test
	@DisplayName("④ 재생성이 여러 번이어도 프롬프트가 누적되지 않는다 — 매번 원본에 적발 표현만 붙인다")
	void regenerationPromptIsRebuiltFromTheOriginalEachTime() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue(SUMMARY_DIRTY)
			.enqueue(SUMMARY_DIRTY)
			.enqueue(SUMMARY_DIRTY);
		NarrativeService service = service(generator, 2);

		service.resolveNewsSummaryNarrative(newsSummary());

		assertThat(generator.callCount()).isEqualTo(3);
		String third = generator.userPrompts().get(2);
		assertThat(third).startsWith(generator.userPrompts().get(0));
		// 누적하면 기사 목록이 중복되고 프롬프트가 계속 길어진다.
		assertThat(countOccurrences(third, REGENERATION_MARKER)).isEqualTo(1);
		assertThat(generator.userPrompts().get(1)).isEqualTo(third);
	}

	// ---------- ⑤(확인 5) NarrativeResultDto의 불변식 ----------

	@Test
	@DisplayName("정적 팩토리 셋이 source와 narrative를 일관되게 짝지어 만든다")
	void staticFactoriesProduceConsistentPairs() {
		assertThat(NarrativeResultDto.llm("문장")).satisfies(result -> {
			assertThat(result.source()).isEqualTo(NarrativeSource.LLM);
			assertThat(result.narrative()).isEqualTo("문장");
			assertThat(result.hasNarrative()).isTrue();
		});
		assertThat(NarrativeResultDto.template("문장")).satisfies(result -> {
			assertThat(result.source()).isEqualTo(NarrativeSource.TEMPLATE);
			assertThat(result.hasNarrative()).isTrue();
		});
		assertThat(NarrativeResultDto.none()).satisfies(result -> {
			assertThat(result.source()).isEqualTo(NarrativeSource.NONE);
			assertThat(result.narrative()).isNull();
			assertThat(result.hasNarrative()).isFalse();
		});
	}

	@Test
	@DisplayName("서비스가 내놓는 결과는 항상 불변식을 지킨다 — NONE이면 서술 없음, 나머지는 서술 있음")
	void serviceResultsAlwaysSatisfyTheInvariant() {
		List<NarrativeResultDto> results = List.of(
			service(new FakeNarrativeGenerator().enqueue(CLEAN_NARRATIVE), 1).resolvePriceMoveNarrative(priceMove()),
			service(new FakeNarrativeGenerator().enqueue(CARD_DIRTY), 1).resolvePriceMoveNarrative(priceMove()),
			service(new FakeNarrativeGenerator().enqueueFailure(), 1).resolvePostSellNarrative(postSell()),
			service(new FakeNarrativeGenerator().enqueue(CLEAN_NARRATIVE), 1)
				.resolveNewsSummaryNarrative(newsSummary()),
			service(new FakeNarrativeGenerator().enqueueFailure(), 1).resolveNewsSummaryNarrative(newsSummary()),
			service(new FakeNarrativeGenerator().enqueue(SUMMARY_DIRTY).enqueue(SUMMARY_DIRTY), 1)
				.resolveNewsSummaryNarrative(newsSummary()));

		for (NarrativeResultDto result : results) {
			if (result.source() == NarrativeSource.NONE) {
				assertThat(result.narrative()).isNull();
			} else {
				assertThat(result.narrative()).isNotBlank();
			}
		}
		// 카드·매도 회고는 NONE이 될 수 없다 — 템플릿이 있기 때문이다 (§C-4).
		assertThat(results.subList(0, 3)).noneMatch(result -> result.source() == NarrativeSource.NONE);
	}

	@Test
	@DisplayName("정규 생성자가 모순 조합을 거부한다 — NONE에 서술이 있거나 LLM·TEMPLATE에 서술이 없으면 실패한다")
	void canonicalConstructorEnforcesTheInvariant() {
		// 이 테스트는 원래 "정규 생성자가 모순 조합을 막지 못한다"를 문서화하던 자리였다. compact 생성자에
		// 검사를 넣으면서 뒤집었다 — 정적 팩토리를 쓰는 규율만으로는 이 클래스를 처음 보는 다음 이슈
		// 구현자에게 불변식이 전달되지 않고, #5가 NONE → summary=NULL → UNAVAILABLE 매핑을 이 짝 위에 얹는다.
		assertThatThrownBy(() -> new NarrativeResultDto("문장", NarrativeSource.NONE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NarrativeResultDto(null, NarrativeSource.LLM))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NarrativeResultDto(null, NarrativeSource.TEMPLATE))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NarrativeResultDto("   ", NarrativeSource.LLM))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NarrativeResultDto("문장", null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("정규 생성자가 올바른 세 조합은 그대로 받는다 — 검사가 정상 경로를 막지 않는다")
	void canonicalConstructorAcceptsValidCombinations() {
		assertThat(new NarrativeResultDto("문장", NarrativeSource.LLM).hasNarrative()).isTrue();
		assertThat(new NarrativeResultDto("문장", NarrativeSource.TEMPLATE).hasNarrative()).isTrue();
		assertThat(new NarrativeResultDto(null, NarrativeSource.NONE).hasNarrative()).isFalse();
	}

	// ---------- 픽스처 ----------

	private NarrativeService service(NarrativeGenerator generator, int maxRegeneration) {
		return new NarrativeService(
			generator,
			new NarrativePromptBuilder(),
			new NarrativeValidator(),
			new NarrativeTemplateBuilder(),
			new FeedbackLlmProperties("gpt-5.4-mini", 20, 512, maxRegeneration, 3));
	}

	private static int countOccurrences(String text, String token) {
		int count = 0;
		int index = text.indexOf(token);
		while (index >= 0) {
			count++;
			index = text.indexOf(token, index + token.length());
		}
		return count;
	}

	private PriceMovePromptDto priceMove() {
		return new PriceMovePromptDto(
			"삼성전자", false, LocalTime.of(9, 32), LocalTime.of(9, 37), 5, new BigDecimal("0.0210"), TRADING_DATE,
			List.of(
				new NewsSourceDto("반도체 공장 가동 일시 중단", "한국경제", TRADING_DATE.atTime(9, 15), false),
				new NewsSourceDto("반도체 업황 둔화 우려 확산", "매일경제", TRADING_DATE.atTime(9, 2), false)));
	}

	private PostSellPromptDto postSell() {
		return new PostSellPromptDto(
			// 시각은 이슈 #275로 LocalDateTime이 됐다 — 주식 픽스처라 같은 원본 거래일에 붙이고
			// multiDayHold=false·MINUTE이라 문장이 이전과 같다.
			"삼성전자", TRADING_DATE.atTime(9, 30), new BigDecimal("70000"), TRADING_DATE.atTime(14, 40),
			new BigDecimal("68500"),
			new BigDecimal("10"), new BigDecimal("-0.0217"), -15207L, new BigDecimal("70800"),
			TRADING_DATE.atTime(11, 5),
			new BigDecimal("-0.0325"), new BigDecimal("68100"), TRADING_DATE.atTime(14, 20), new BigDecimal("0.0059"),
			null, null, List.of(), null, null, null, null, null, null, false, HoldHighBasis.MINUTE);
	}

	// sameSessionCompleted=false — 보유 구간 극값 6필드가 전부 null이다 (spec §파생 사실 계산).
	// 위 postSell()이 극값을 항상 채우고 있어 이 경로가 한 번도 돌지 않았다.
	private PostSellPromptDto multiSessionPostSell() {
		return new PostSellPromptDto(
			"삼성전자", TRADING_DATE.atTime(9, 30), new BigDecimal("70000"), TRADING_DATE.atTime(14, 40),
			new BigDecimal("68500"),
			new BigDecimal("10"), new BigDecimal("-0.0217"), -15207L, null, null,
			null, null, null, null,
			null, null, List.of(), null, null, null, null, null, null, false, HoldHighBasis.MINUTE);
	}

	private NewsSummaryPromptDto newsSummary() {
		return new NewsSummaryPromptDto(
			"삼성전자", NewsSummaryScope.PRE_MARKET, TRADING_DATE,
			List.of(new NewsSourceDto(
				"반도체 업황 둔화 우려 확산", "매일경제", TRADING_DATE.minusDays(1).atTime(18, 40), false)));
	}

	private MarketBriefingPromptDto briefing() {
		return new MarketBriefingPromptDto(
			Market.STOCK, TRADING_DATE,
			List.of(new BriefingNewsItemDto(
				"삼성전자",
				new NewsSourceDto(
					"반도체 업황 둔화 우려 확산", "매일경제", TRADING_DATE.minusDays(1).atTime(18, 40), false))));
	}
}
