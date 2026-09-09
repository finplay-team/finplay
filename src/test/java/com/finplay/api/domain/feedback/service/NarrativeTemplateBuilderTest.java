package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class NarrativeTemplateBuilderTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);

	private final NarrativeTemplateBuilder builder = new NarrativeTemplateBuilder();

	private final NarrativeValidator validator = new NarrativeValidator();

	private final NarrativeNumberValidator numberValidator = new NarrativeNumberValidator();

	private final NarrativePromptBuilder promptBuilder = new NarrativePromptBuilder();


	@Test
	@DisplayName("장중 카드 템플릿이 §템플릿 문장 표대로 조립된다")
	void intradayCardTemplateMatchesSpecTable() {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("0.0210"), 2));

		assertThat(template).isEqualTo("09:32부터 5분간 2.10% 상승했습니다. 같은 시간대에 기사 2건이 있었습니다.");
	}

	@Test
	@DisplayName("코인 카드가 자정을 넘어도 구간 길이가 음수가 되지 않는다 — 23:58~00:03은 5분간이다")
	void intradayCardTemplateHandlesMidnightCrossing() {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(23, 58), LocalTime.of(0, 3), 5, rate("0.0120"), 1));

		assertThat(template).isEqualTo("23:58부터 5분간 1.20% 상승했습니다. 같은 시간대에 기사 1건이 있었습니다.");
		assertThat(template).doesNotContain("-1435");
	}

	@Test
	@DisplayName("시가 갭 템플릿이 §템플릿 문장 표대로 조립된다")
	void openingGapTemplateMatchesSpecTable() {
		String template = builder.priceMoveTemplate(card(true, null, null, rate("0.0235"), 3));

		assertThat(template).isEqualTo("직전 거래일 종가 대비 2.35% 높게 시작했습니다. 개장 전 뉴스·공시 3건이 있었습니다.");
	}

	@Test
	@DisplayName("매도 회고 템플릿이 §템플릿 문장 표대로 세 문장으로 조립된다")
	void postSellTemplateMatchesSpecTable() {
		String template = builder.postSellTemplate(
			postSell(rate("-0.0217"), money("70800"), LocalTime.of(11, 5)));

		assertThat(template).isEqualTo(
			"70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다. 보유 중 최고가는 11:05의 70,800원이었습니다.");
	}

	@Test
	@DisplayName("매도 회고 템플릿은 일기가 있어도 한 글자도 달라지지 않는다")
	void postSellTemplateIgnoresJournalsEntirely() {
		PostSellPromptDto withoutJournals = postSell(rate("-0.0217"), money("70800"), LocalTime.of(11, 5));
		PostSellPromptDto withJournals = new PostSellPromptDto(
			withoutJournals.instrumentName(), withoutJournals.buyAt(), withoutJournals.buyPrice(),
			withoutJournals.sellAt(), withoutJournals.sellPrice(), withoutJournals.quantity(),
			withoutJournals.returnRate(), withoutJournals.realizedPnl(), withoutJournals.holdHighPrice(),
			withoutJournals.holdHighAt(), withoutJournals.sellVsHighRate(), withoutJournals.holdLowPrice(),
			withoutJournals.holdLowAt(), withoutJournals.sellVsLowRate(), withoutJournals.buyToNewsMinutes(),
			withoutJournals.firstNewsAt(), withoutJournals.priceMoves(), withoutJournals.closePrice(),
			withoutJournals.sellToCloseRate(), withoutJournals.holderCount(), withoutJournals.soldWithin30MinRate(),
			withoutJournals.medianMinutesToSell(), withoutJournals.yourMinutesToSell(),
			withoutJournals.multiDayHold(), withoutJournals.holdHighBasis(),
			List.of(new BuyJournalLineDto(TRADING_DATE.atTime(9, 30), "반등을 기대하고 들어갔습니다.")),
			"불안해 정리했습니다.");

		assertThat(builder.postSellTemplate(withJournals))
			.isEqualTo(builder.postSellTemplate(withoutJournals))
			.doesNotContain("반등을 기대하고")
			.doesNotContain("불안해 정리했습니다");
	}


	static Stream<String> everyTemplateSentence() {
		NarrativeTemplateBuilder builder = new NarrativeTemplateBuilder();
		return Stream.of(
			builder.priceMoveTemplate(fixtureCard(false, LocalTime.of(9, 32), LocalTime.of(9, 37), "0.0210", 2)),
			builder.priceMoveTemplate(fixtureCard(false, LocalTime.of(11, 20), LocalTime.of(11, 25), "-0.0182", 1)),
			builder.priceMoveTemplate(fixtureCard(false, LocalTime.of(14, 0), LocalTime.of(14, 30), "0", 5)),
			builder.priceMoveTemplate(fixtureCard(false, LocalTime.of(9, 0), LocalTime.of(15, 30), "-0.1234", 9)),
			builder.priceMoveTemplate(fixtureCard(true, null, null, "0.0235", 3)),
			builder.priceMoveTemplate(fixtureCard(true, null, null, "-0.0140", 1)),
			builder.postSellTemplate(fixturePostSell("-0.0217", "70800", LocalTime.of(11, 5))),
			builder.postSellTemplate(fixturePostSell("0.0305", "72500", LocalTime.of(13, 41))),
			builder.postSellTemplate(fixturePostSell("0", "70000", LocalTime.of(9, 30))),
			builder.postSellTemplate(fixturePostSell("-0.0217", null, null)),
			builder.postSellTemplate(fixturePostSell("0.0305", null, null)));
	}

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("everyTemplateSentence")
	@DisplayName("모든 갈래의 템플릿 문장이 카드·매도 회고 후검증(5줄 37표현)을 통과한다")
	void everyTemplateSentencePassesCardValidation(String sentence) {
		NarrativeValidationDto result = validator.validateCardOrPostSell(sentence);

		assertThat(result.detectedExpressions()).as("적발된 표현: %s", result.detectedExpressions()).isEmpty();
		assertThat(result.passed()).isTrue();
	}

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("everyTemplateSentence")
	@DisplayName("같은 문장이 요약·브리핑 후검증(4줄 26표현)도 통과한다")
	void everyTemplateSentencePassesSummaryValidation(String sentence) {
		assertThat(validator.validateSummaryOrBriefing(sentence).passed()).isTrue();
	}

	@Test
	@DisplayName("템플릿에 쓰인 어미가 §후검증의 가정법·서술형 경계 안에 있다 — 했습니다/이었습니다는 했다면/었다면이 아니다")
	void templateEndingsStayInsideTheValidationBoundary() {
		String card = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("0.0210"), 2));
		String postSell = builder.postSellTemplate(
			postSell(rate("-0.0217"), money("70800"), LocalTime.of(11, 5)));

		assertThat(card).contains("상승했습니다").doesNotContain("했다면", "렸다면", "았다면", "었다면", "였다면");
		assertThat(postSell).contains("매도했습니다", "이었습니다")
			.doesNotContain("했다면", "렸다면", "았다면", "었다면", "였다면");
		assertThat(postSell).doesNotContain("매도하세요", "하세요");
	}


	@Test
	@DisplayName("부호 1 — 장중 상승은 절댓값 + 상승, 부호 문자가 붙지 않는다")
	void signCase1IntradayRise() {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("0.0210"), 2));

		assertThat(template).contains("2.10% 상승했습니다");
		assertThat(template).doesNotContain("+", "-");
	}

	@Test
	@DisplayName("부호 2 — 장중 하락은 절댓값 + 하락, \"-1.82% 하락\"이 되지 않는다")
	void signCase2IntradayFall() {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(11, 20), LocalTime.of(11, 25), rate("-0.0182"), 1));

		assertThat(template).contains("1.82% 하락했습니다");
		assertThat(template).doesNotContain("-1.82");
		assertThat(template).doesNotContain("+", "-");
	}

	@Test
	@DisplayName("부호 3 — 갭 상승은 절댓값 + 높게")
	void signCase3GapUp() {
		String template = builder.priceMoveTemplate(card(true, null, null, rate("0.0235"), 3));

		assertThat(template).contains("2.35% 높게 시작했습니다");
		assertThat(template).doesNotContain("+", "-", "낮게");
	}

	@Test
	@DisplayName("부호 4 — 갭 하락은 절댓값 + 낮게")
	void signCase4GapDown() {
		String template = builder.priceMoveTemplate(card(true, null, null, rate("-0.0140"), 1));

		assertThat(template).contains("1.40% 낮게 시작했습니다");
		assertThat(template).doesNotContain("-1.40");
		assertThat(template).doesNotContain("+", "-", "높게");
	}

	@Test
	@DisplayName("부호 5 — 수익률 양수는 방향 단어가 없으므로 +를 찍는다")
	void signCase5PositiveReturnKeepsPlusSign() {
		String template = builder.postSellTemplate(
			postSell(rate("0.0305"), money("72500"), LocalTime.of(13, 41)));

		assertThat(template).contains("수익률은 +3.05%입니다");
	}

	@Test
	@DisplayName("부호 6 — 수익률 음수는 -를 그대로 남긴다")
	void signCase6NegativeReturnKeepsMinusSign() {
		String template = builder.postSellTemplate(
			postSell(rate("-0.0217"), money("70800"), LocalTime.of(11, 5)));

		assertThat(template).contains("수익률은 -2.17%입니다");
	}

	@Test
	@DisplayName("부호가 반대인 같은 크기의 변동은 숫자가 같고 방향 단어만 달라진다")
	void oppositeSignsDifferOnlyByTheDirectionWord() {
		String rise = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("0.0182"), 2));
		String fall = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("-0.0182"), 2));

		assertThat(rise).contains("1.82%");
		assertThat(fall).contains("1.82%");
		assertThat(rise.replace("상승", "하락")).isEqualTo(fall);
	}

	@Test
	@DisplayName("[경계 문서화] 변동률 0은 상승·높게 쪽으로 떨어지고 수익률 0은 +0.00%가 된다")
	void zeroRateFallsToThePositiveWording() {
		assertThat(builder.priceMoveTemplate(card(false, LocalTime.of(14, 0), LocalTime.of(14, 30), rate("0"), 5)))
			.isEqualTo("14:00부터 30분간 0.00% 상승했습니다. 같은 시간대에 기사 5건이 있었습니다.");
		assertThat(builder.priceMoveTemplate(card(true, null, null, rate("0"), 1)))
			.isEqualTo("직전 거래일 종가 대비 0.00% 높게 시작했습니다. 개장 전 뉴스·공시 1건이 있었습니다.");
		assertThat(builder.postSellTemplate(postSell(rate("0"), null, null)))
			.contains("수익률은 +0.00%입니다");
	}


	@Test
	@DisplayName("holdHighBasis=DAILY면 셋째 문장이 시·분 없이 \"M월 d일 종가\"로 적힌다")
	void postSellTemplateWritesDailyExtremeAsAClosingPrice() {
		String template = builder.postSellTemplate(multiDayPostSell(HoldHighBasis.DAILY, TRADING_DATE.atTime(23, 59)));

		assertThat(template).isEqualTo(
			"70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다. 보유 중 최고가는 8월 3일 종가의 70,800원이었습니다.");
		assertThat(template).doesNotContain("23:59");
	}

	@Test
	@DisplayName("MINUTE이면서 하루를 넘긴 보유면 셋째 문장이 날짜 + 시·분으로 적힌다")
	void postSellTemplateKeepsClockTimeForMinuteExtremesAcrossDays() {
		String template = builder.postSellTemplate(multiDayPostSell(HoldHighBasis.MINUTE, TRADING_DATE.atTime(11, 5)));

		assertThat(template).isEqualTo(
			"70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다. 보유 중 최고가는 8월 3일 11:05의 70,800원이었습니다.");
		assertThat(template).doesNotContain("종가의");
	}

	@Test
	@DisplayName("주식 조합(multiDayHold=false + MINUTE)의 템플릿 문장은 날짜 없이 시·분만 적는다")
	void postSellTemplateKeepsTheStockCombinationUnchanged() {
		String template = builder.postSellTemplate(
			postSell(rate("-0.0217"), money("70800"), LocalTime.of(11, 5)));

		assertThat(template).isEqualTo(
			"70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다. 보유 중 최고가는 11:05의 70,800원이었습니다.");
		assertThat(template).doesNotContain("8월").doesNotContain("종가의");
	}

	@Test
	@DisplayName("날짜·종가 표기가 붙은 문장도 카드·매도 회고 후검증을 통과한다")
	void newMomentPhrasingsStillPassValidation() {
		Stream.of(
			builder.postSellTemplate(multiDayPostSell(HoldHighBasis.DAILY, TRADING_DATE.atTime(23, 59))),
			builder.postSellTemplate(multiDayPostSell(HoldHighBasis.MINUTE, TRADING_DATE.atTime(11, 5))))
			.forEach(sentence -> {
				NarrativeValidationDto result = validator.validateCardOrPostSell(sentence);
				assertThat(result.detectedExpressions())
					.as("적발된 표현: %s (문장: %s)", result.detectedExpressions(), sentence)
					.isEmpty();
				assertThat(result.passed()).isTrue();
			});
	}


	static Stream<PostSellPromptDto> everyPostSellShape() {
		return Stream.of(
			fixturePostSell("-0.0217", "70800", LocalTime.of(11, 5)),
			fixturePostSell("0.0305", "72500", LocalTime.of(13, 41)),
			fixturePostSell("0", "70000", LocalTime.of(9, 30)),
			fixturePostSell("-0.0217", null, null),
			fixturePostSell("0.0305", null, null),
			multiDayPostSell(HoldHighBasis.DAILY, TRADING_DATE.atTime(23, 59)),
			multiDayPostSell(HoldHighBasis.MINUTE, TRADING_DATE.atTime(11, 5)));
	}

	@ParameterizedTest(name = "[{index}]")
	@MethodSource("everyPostSellShape")
	@DisplayName("매도 회고 템플릿 문장의 수치가 전부 같은 입력의 프롬프트에서 온다 — 폴백이 스스로 위반이 아니다")
	void postSellTemplateNumbersAllComeFromTheSamePrompt(PostSellPromptDto input) {
		String template = builder.postSellTemplate(input);
		String prompt = promptBuilder.postSellPrompt(input);

		NarrativeValidationDto result = numberValidator.validate(template, prompt);

		assertThat(result.detectedExpressions())
			.as("출처 없는 수치: %s (문장: %s)", result.detectedExpressions(), template)
			.isEmpty();
	}

	@Test
	@DisplayName("카드 템플릿의 `5분간`은 카드 프롬프트에 없다 — 그래서 카드에는 숫자 대조를 걸지 않는다")
	void priceMoveTemplateWouldFailTheNumberCheckWhichIsWhyTheCardIsExcluded() {
		PriceMovePromptDto input = fixtureCard(false, LocalTime.of(9, 32), LocalTime.of(9, 37), "0.0210", 2);
		String template = builder.priceMoveTemplate(input);
		String prompt = promptBuilder.priceMovePrompt(input);

		assertThat(template).contains("5분간");
		assertThat(prompt).doesNotContain("5분");
		assertThat(numberValidator.validate(template, prompt).detectedExpressions()).containsExactly("5");
	}


	@Test
	@DisplayName("sameSessionCompleted=false면 셋째 문장이 통째로 빠지고 앞 두 문장은 그대로다")
	void postSellTemplateDropsThirdSentenceWhenHoldHighIsAbsent() {
		String template = builder.postSellTemplate(postSell(rate("-0.0217"), null, null));

		assertThat(template).isEqualTo("70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다.");
		assertThat(template).doesNotContain("null");
		assertThat(template).doesNotContain("보유 중 최고가");
		assertThat(template).endsWith("입니다.").isEqualTo(template.strip());
	}

	@Test
	@DisplayName("holdHighPrice나 holdHighAt 중 하나만 없어도 셋째 문장이 빠진다 — 한쪽만으로 문장을 만들지 않는다")
	void postSellTemplateDropsThirdSentenceWhenEitherHoldHighFieldIsAbsent() {
		String priceOnly = builder.postSellTemplate(postSell(rate("-0.0217"), money("70800"), null));
		String timeOnly = builder.postSellTemplate(postSell(rate("-0.0217"), null, LocalTime.of(11, 5)));

		assertThat(priceOnly).doesNotContain("보유 중 최고가").doesNotContain("null");
		assertThat(timeOnly).doesNotContain("보유 중 최고가").doesNotContain("null");
		assertThat(priceOnly).isEqualTo(timeOnly);
	}

	@Test
	@DisplayName("셋째 문장이 빠져도 서술은 비지 않는다 — narrativeStatus는 항상 READY다")
	void postSellTemplateIsNeverBlank() {
		String template = builder.postSellTemplate(postSell(rate("0"), null, null));

		assertThat(template).isNotBlank();
		assertThat(validator.validateCardOrPostSell(template).passed()).isTrue();
	}


	@ParameterizedTest
	@CsvSource({
		"999, '999원'",
		"1000, '1,000원'",
		"70000, '70,000원'",
		"1234567, '1,234,567원'"})
	@DisplayName("금액은 천 단위 구분 기호를 쓰고 1,000 미만에는 붙이지 않는다")
	void moneyUsesThousandSeparators(String price, String expected) {
		String template = builder.postSellTemplate(
			postSellWithPrices(money(price), money(price), rate("0")));

		assertThat(template).startsWith(expected + "에 매수해 " + expected + "에 매도했습니다.");
	}

	@ParameterizedTest
	@CsvSource({
		"0.02, 2.00%",
		"0.1, 10.00%",
		"0.0182, 1.82%",
		"0.000049, 0.00%",
		"0.021049, 2.10%",
		"0.021050, 2.11%"})
	@DisplayName("변동률은 항상 소수 둘째 자리까지 적고 HALF_UP으로 반올림한다")
	void percentAlwaysUsesTwoDecimalsWithHalfUp(String rawRate, String expected) {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate(rawRate), 1));

		assertThat(template).contains(expected + " 상승했습니다");
	}

	@Test
	@DisplayName("금액도 소수를 HALF_UP으로 반올림해 원 단위 정수로 적는다")
	void moneyRoundsHalfUpToWholeWon() {
		assertThat(builder.postSellTemplate(postSellWithPrices(money("68500.4"), money("68500.5"), rate("0"))))
			.startsWith("68,500원에 매수해 68,501원에 매도했습니다.");
	}

	@ParameterizedTest
	@CsvSource({
		"9, 5, 09:05",
		"11, 5, 11:05",
		"0, 0, 00:00",
		"15, 30, 15:30"})
	@DisplayName("시각은 HH:mm으로 0을 채워 적는다")
	void timeIsZeroPaddedHourMinute(int hour, int minute, String expected) {
		String card = builder.priceMoveTemplate(
			card(false, LocalTime.of(hour, minute), LocalTime.of(hour, minute).plusMinutes(5), rate("0.01"), 1));
		String postSell = builder.postSellTemplate(
			postSell(rate("0.01"), money("70800"), LocalTime.of(hour, minute)));

		assertThat(card).startsWith(expected + "부터 5분간");
		assertThat(postSell).contains("보유 중 최고가는 " + expected + "의 70,800원이었습니다.");
	}

	@ParameterizedTest
	@CsvSource({
		"9, 32, 9, 37, 5분간",
		"9, 0, 15, 30, 390분간",
		"11, 20, 11, 21, 1분간"})
	@DisplayName("구간 길이는 windowStart~windowEnd에서 분 단위로 계산한다")
	void windowMinutesAreComputedFromTheWindow(
		int startHour, int startMinute, int endHour, int endMinute, String expected) {
		String template = builder.priceMoveTemplate(card(
			false, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute), rate("0.01"), 1));

		assertThat(template).contains(expected);
	}

	@ParameterizedTest
	@CsvSource({"1, 기사 1건", "2, 기사 2건", "10, 기사 10건"})
	@DisplayName("근거 기사 건수는 sources 크기를 그대로 센다")
	void sourceCountMatchesTheInputList(int count, String expected) {
		String template = builder.priceMoveTemplate(
			card(false, LocalTime.of(9, 32), LocalTime.of(9, 37), rate("0.01"), count));

		assertThat(template).contains(expected);
	}


	private static BigDecimal rate(String value) {
		return new BigDecimal(value);
	}

	private static BigDecimal money(String value) {
		return value == null ? null : new BigDecimal(value);
	}

	private PriceMovePromptDto card(
		boolean openingGap, LocalTime windowStart, LocalTime windowEnd, BigDecimal changeRate, int sourceCount) {
		int windowMinutes = openingGap
			? 0
			: (int)ChronoUnit.MINUTES.between(windowStart, windowEnd);
		return card(openingGap, windowStart, windowEnd, windowMinutes, changeRate, sourceCount);
	}

	private PriceMovePromptDto card(boolean openingGap, LocalTime windowStart, LocalTime windowEnd,
		int windowMinutes, BigDecimal changeRate, int sourceCount) {
		return new PriceMovePromptDto("삼성전자", openingGap, windowStart, windowEnd, windowMinutes, changeRate,
			TRADING_DATE, sources(sourceCount));
	}

	private static PriceMovePromptDto fixtureCard(
		boolean openingGap, LocalTime windowStart, LocalTime windowEnd, String changeRate, int sourceCount) {
		int windowMinutes = openingGap
			? 0
			: (int)ChronoUnit.MINUTES.between(windowStart, windowEnd);
		return new PriceMovePromptDto("삼성전자", openingGap, windowStart, windowEnd, windowMinutes,
			new BigDecimal(changeRate), TRADING_DATE, sources(sourceCount));
	}

	private static List<NewsSourceDto> sources(int count) {
		return IntStream.range(0, count)
			.mapToObj(index -> new NewsSourceDto(
				"기사 제목 " + index, "한국경제", TRADING_DATE.atTime(11, 15), false))
			.toList();
	}

	private PostSellPromptDto postSell(BigDecimal returnRate, BigDecimal holdHighPrice, LocalTime holdHighAt) {
		return fixturePostSell(returnRate, holdHighPrice, holdHighAt, money("70000"), money("68500"));
	}

	private static PostSellPromptDto multiDayPostSell(HoldHighBasis basis, LocalDateTime holdHighAt) {
		return new PostSellPromptDto(
			"비트코인", LocalDate.of(2026, 8, 1).atTime(14, 20), money("70000"),
			LocalDate.of(2026, 8, 5).atTime(9, 5), money("68500"), new BigDecimal("10"),
			new BigDecimal("-0.0217"), -15207L,
			money("70800"), holdHighAt, new BigDecimal("-0.0325"),
			money("68100"), TRADING_DATE.atTime(14, 20), new BigDecimal("0.0059"), null, null, List.of(),
			null, null, null, null, null, null, true, basis, List.of(), null);
	}

	private PostSellPromptDto postSellWithPrices(BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal returnRate) {
		return fixturePostSell(returnRate, null, null, buyPrice, sellPrice);
	}

	private static PostSellPromptDto fixturePostSell(
		String returnRate, String holdHighPrice, LocalTime holdHighAt) {
		return fixturePostSell(
			new BigDecimal(returnRate), money(holdHighPrice), holdHighAt,
			new BigDecimal("70000"), new BigDecimal("68500"));
	}

	private static PostSellPromptDto fixturePostSell(
		BigDecimal returnRate, BigDecimal holdHighPrice, LocalTime holdHighAt,
		BigDecimal buyPrice, BigDecimal sellPrice) {
		return new PostSellPromptDto(
			"삼성전자", TRADING_DATE.atTime(9, 30), buyPrice, TRADING_DATE.atTime(14, 40), sellPrice,
			new BigDecimal("10"),
			returnRate, -15207L, holdHighPrice, holdHighAt == null ? null : TRADING_DATE.atTime(holdHighAt),
			holdHighPrice == null ? null : new BigDecimal("-0.0325"),
			new BigDecimal("68100"), TRADING_DATE.atTime(14, 20), new BigDecimal("0.0059"), null, null, List.of(),
			null, null, null, null, null, null, false, HoldHighBasis.MINUTE, List.of(), null);
	}
}
