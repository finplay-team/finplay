// NarrativePromptBuilder가 조립한 프롬프트가 spec §LLM 프롬프트의 예시와 글자 단위로 같은지 검증하는 단위 테스트.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 외부 의존이 전혀 없는 문자열 조립이라 mock이 없다.
 *
 * <p>아래 기대 문자열은 spec §LLM 프롬프트의 예시 블록을 그대로 옮긴 <b>골든 마스터</b>다. 프롬프트가
 * 조용히 망가지면 예외는 안 나고 서술 품질만 떨어져 원인을 찾기 어렵다 — 그래서 "포함"이 아니라
 * "완전 일치"로 못 박는다. spec을 바꾸면 이 문자열도 같은 커밋에서 바꾼다.
 */
class NarrativePromptBuilderTest {

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalDate PREVIOUS_DATE = LocalDate.of(2026, 8, 2);

	private final NarrativePromptBuilder builder = new NarrativePromptBuilder();

	// ---------- 시스템 프롬프트 ----------

	@Test
	@DisplayName("시스템 프롬프트가 spec §LLM 프롬프트 블록과 글자 단위로 같다")
	void systemPromptMatchesSpecExactly() {
		String expected = """
			너는 모의투자 교육 서비스의 관찰자다. 주어진 수치와 기사 목록을 한국어로 서술한다.

			규칙:
			- 주어진 수치만 쓴다. 계산하거나 바꾸지 않는다.
			- 기사와 가격 변동의 인과를 단정하지 않는다. "같은 시간대에 이런 기사가 있었다" 수준으로만 쓴다.
			- 특정 종목의 매수·매도를 권유하지 않는다.
			- 앞으로의 가격을 예측하지 않는다.
			- 조언하지 않는다. 관찰한 사실만 서술한다.
			- 모든 문장을 "~습니다"로 끝낸다.
			- **기사 제목을 그대로 옮기지 않는다.** 제목에 담긴 전망·기대·예측 표현을 따라 쓰지 말고,
			  무엇을 다룬 기사인지만 네 말로 서술한다.""";

		assertThat(builder.systemPrompt()).isEqualTo(expected);
	}

	@Test
	@DisplayName("시스템 프롬프트의 규칙이 정확히 7줄이고 한 줄도 빠지지 않았다")
	void systemPromptKeepsAllSevenRules() {
		String systemPrompt = builder.systemPrompt();

		// 한 줄이 빠지면 §후검증 적발률만 올라가고 원인이 프롬프트라는 걸 알기 어렵다. 개수와 내용을 둘 다 못 박는다.
		List<String> ruleHeads = systemPrompt.lines().filter(line -> line.startsWith("- ")).toList();
		assertThat(ruleHeads).hasSize(7);

		assertThat(systemPrompt).containsSubsequence(
			"- 주어진 수치만 쓴다. 계산하거나 바꾸지 않는다.",
			"- 기사와 가격 변동의 인과를 단정하지 않는다.",
			"- 특정 종목의 매수·매도를 권유하지 않는다.",
			"- 앞으로의 가격을 예측하지 않는다.",
			"- 조언하지 않는다. 관찰한 사실만 서술한다.",
			"- 모든 문장을 \"~습니다\"로 끝낸다.",
			"- **기사 제목을 그대로 옮기지 않는다.**");
	}

	// ---------- 파트별 골든 마스터 ----------

	@Test
	@DisplayName("변동 원인 카드 프롬프트가 spec 예시와 완전히 일치한다")
	void priceMovePromptMatchesSpecExample() {
		String expected = """
			종목: 삼성전자
			구간: 11:20 ~ 11:25
			변동률: -1.82%

			같은 시간대 기사:
			- 삼성전자, 반도체 공장 가동 일시 중단 (한국경제, 11:15)
			- 반도체 업황 둔화 우려 확산 (매일경제, 11:02)

			위 내용을 2~3문장으로 서술해줘.""";

		assertThat(builder.priceMovePrompt(specIntradayCard())).isEqualTo(expected);
	}

	@Test
	@DisplayName("매도 회고 프롬프트가 spec 예시와 완전히 일치한다")
	void postSellPromptMatchesSpecExample() {
		String expected = """
			종목: 삼성전자
			매수: 09:30, 70,000원 10주
			매도: 14:40, 68,500원 10주
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

		assertThat(builder.postSellPrompt(specPostSell())).isEqualTo(expected);
	}

	@Test
	@DisplayName("종목 뉴스 요약 프롬프트가 spec 예시와 완전히 일치한다")
	void newsSummaryPromptMatchesSpecExample() {
		String expected = """
			종목: 삼성전자
			범위: 직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전

			기사:
			- 반도체 업황 둔화 우려 확산 (매일경제, 전일 18:40)
			- 삼성전자, 유상증자 결정 (DART 공시, 전일 접수)

			위 기사들을 종합해 3~5문장으로 서술해줘.
			특정 기사의 문장을 그대로 옮기지 말고, 무엇을 다룬 기사들인지 써줘.""";

		assertThat(builder.newsSummaryPrompt(specNewsSummary(NewsSummaryScope.PRE_MARKET))).isEqualTo(expected);
	}

	@Test
	@DisplayName("개장 전 브리핑 프롬프트가 spec 예시와 완전히 일치한다")
	void marketBriefingPromptMatchesSpecExample() {
		String expected = """
			시장: 국내 주식
			범위: 직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전

			기사:
			- [삼성전자] 반도체 업황 둔화 우려 확산 (매일경제, 전일 18:40)
			- [SK하이닉스] 미국 증시 반도체 업종 강세 (한국경제, 당일 06:20)
			- [LG화학] 주요사항보고서 유상증자결정 (DART 공시, 전일 접수)

			위 내용을 3~6문장으로 서술해줘.
			종목명을 언급해도 되지만 사거나 팔라고 하지 마라.
			어떤 종목에 어떤 소식이 있었는지만 써줘.""";

		assertThat(builder.marketBriefingPrompt(specBriefing(Market.STOCK))).isEqualTo(expected);
	}

	// ---------- 검증 조건 ① 파트별 문장 수 지시 ----------

	@Test
	@DisplayName("네 파트의 문장 수 지시가 spec대로 각각 다르다 — 2~3 / 3~4 / 3~5 / 3~6")
	void eachPartCarriesItsOwnSentenceCountDirective() {
		String card = builder.priceMovePrompt(specIntradayCard());
		String postSell = builder.postSellPrompt(specPostSell());
		String summary = builder.newsSummaryPrompt(specNewsSummary(NewsSummaryScope.PRE_MARKET));
		String briefing = builder.marketBriefingPrompt(specBriefing(Market.STOCK));

		assertThat(card).contains("위 내용을 2~3문장으로 서술해줘.");
		assertThat(postSell).contains("위 내용을 3~4문장으로 서술해줘.");
		assertThat(summary).contains("위 기사들을 종합해 3~5문장으로 서술해줘.");
		assertThat(briefing).contains("위 내용을 3~6문장으로 서술해줘.");

		// 한 파트의 지시를 다른 파트에 복사해 넣는 사고를 잡는다 — 네 값은 서로 달라야 한다.
		assertThat(List.of(
			sentenceRange(card), sentenceRange(postSell), sentenceRange(summary), sentenceRange(briefing)))
			.containsExactly("2~3", "3~4", "3~5", "3~6")
			.doesNotHaveDuplicates();
	}

	// ---------- 검증 조건 ② 요약의 범위 줄이 범위별로 다름 ----------

	@Test
	@DisplayName("NewsSummaryScope 세 값의 범위 문구가 spec §C-2 그대로이고 서로 다르다")
	void newsSummaryScopeTextsMatchSpecAndAreDistinct() {
		assertThat(NewsSummaryScope.PRE_MARKET.promptText())
			.isEqualTo("직전 거래일 장 마감(15:30) 이후 ~ 당일 개장(09:00) 전");
		assertThat(NewsSummaryScope.FULL.promptText()).isEqualTo("직전 거래일 15:30 ~ 원본 거래일 15:30");
		assertThat(NewsSummaryScope.ROLLING_24H.promptText()).isEqualTo("최근 24시간");

		assertThat(Arrays.stream(NewsSummaryScope.values()).map(NewsSummaryScope::promptText).toList())
			.doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@EnumSource(NewsSummaryScope.class)
	@DisplayName("요약 프롬프트의 범위 줄이 scope마다 그 scope의 문구 하나만 담는다")
	void newsSummaryPromptCarriesOnlyItsOwnScopeLine(NewsSummaryScope scope) {
		String prompt = builder.newsSummaryPrompt(specNewsSummary(scope));

		assertThat(prompt.lines().toList()).contains("범위: " + scope.promptText());
		// PRE_MARKET 생성에 FULL 기사가 섞이는 사고를 모델이 알아채려면 범위가 하나만 실려야 한다.
		for (NewsSummaryScope other : NewsSummaryScope.values()) {
			if (other != scope) {
				assertThat(prompt).doesNotContain("범위: " + other.promptText());
			}
		}
	}

	@Test
	@DisplayName("같은 기사 목록이라도 scope가 다르면 프롬프트 문자열이 실제로 달라진다")
	void newsSummaryPromptsDifferAcrossScopes() {
		List<String> prompts = new ArrayList<>();
		for (NewsSummaryScope scope : NewsSummaryScope.values()) {
			prompts.add(builder.newsSummaryPrompt(specNewsSummary(scope)));
		}

		assertThat(prompts).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("코인 브리핑은 시장·범위 줄이 주식과 다르다 — 최근 24시간을 쓴다")
	void cryptoBriefingUsesRollingWindowScope() {
		String crypto = builder.marketBriefingPrompt(specBriefing(Market.CRYPTO));

		assertThat(crypto.lines().toList())
			.containsSequence("시장: 코인", "범위: " + NewsSummaryScope.ROLLING_24H.promptText());
		assertThat(crypto).doesNotContain("국내 주식");
		assertThat(crypto).isNotEqualTo(builder.marketBriefingPrompt(specBriefing(Market.STOCK)));
	}

	// ---------- 검증 조건 ③ 재생성 프롬프트에 적발 표현이 실제로 포함 ----------

	@Test
	@DisplayName("재생성 프롬프트가 1차 프롬프트 + spec §후검증 확정 문구이고 적발 표현이 그대로 들어간다")
	void regenerationPromptEmbedsDetectedExpressionsVerbatim() {
		String original = builder.newsSummaryPrompt(specNewsSummary(NewsSummaryScope.PRE_MARKET));

		String regenerated = builder.regenerationPrompt(original, List.of("전망", "기대됩니다"));

		String expected = original + """


			직전 출력이 아래 금지 표현에 걸려 폐기됐다: 전망, 기대됩니다

			같은 표현을 쓰지 말고 다시 써라. 기사 제목을 그대로 인용하지 마라 —
			제목에 든 전망·기대·예측 표현이 그대로 따라 들어온다.
			"업황 전망을 다룬 기사"처럼 쓰지 말고 "업황을 다룬 기사"처럼 써라.""";
		assertThat(regenerated).isEqualTo(expected);
	}

	@Test
	@DisplayName("적발 표현이 여러 개여도 개수로 요약하지 않고 표현 자체를 전부 넣는다")
	void regenerationPromptContainsEveryDetectedExpression() {
		List<String> detected = List.of("때문에", "주목할", "오를 것", "하세요", "기회를");

		String regenerated = builder.regenerationPrompt("사용자 프롬프트", detected);

		assertThat(regenerated).contains(detected);
		assertThat(regenerated).contains("직전 출력이 아래 금지 표현에 걸려 폐기됐다: 때문에, 주목할, 오를 것, 하세요, 기회를");
		// 자리표시자가 치환되지 않고 남으면 모델은 무엇에 걸렸는지 알 수 없다.
		assertThat(regenerated).doesNotContain("{적발된 표현들}");
	}

	@Test
	@DisplayName("재생성 프롬프트는 1차 사용자 프롬프트를 통째로 유지한다 — 기사 목록이 빠지면 2차 재료가 없다")
	void regenerationPromptKeepsTheOriginalUserPrompt() {
		String original = builder.newsSummaryPrompt(specNewsSummary(NewsSummaryScope.PRE_MARKET));

		String regenerated = builder.regenerationPrompt(original, List.of("전망"));

		assertThat(regenerated).startsWith(original);
		assertThat(regenerated).contains("반도체 업황 둔화 우려 확산 (매일경제, 전일 18:40)");
	}

	// ---------- 갭 카드 분기 ----------

	@Test
	@DisplayName("갭 카드는 구간 줄과 기사 머리말이 장중 카드와 다르다")
	void openingGapCardUsesItsOwnWindowAndNewsHeading() {
		String expected = """
			종목: 삼성전자
			구간: 개장 시가 (직전 거래일 종가 대비)
			변동률: +2.35%

			개장 전 기사:
			- 삼성전자, 유상증자 결정 (DART 공시, 전일 접수)
			- 미국 증시 반도체 업종 강세 (한국경제, 전일 22:40)

			위 내용을 2~3문장으로 서술해줘.""";

		assertThat(builder.priceMovePrompt(specGapCard())).isEqualTo(expected);
	}

	@Test
	@DisplayName("갭 카드와 장중 카드가 실제로 다른 문자열을 낸다 — 갭에는 시각 구간도 '같은 시간대'도 없다")
	void openingGapCardDiffersFromIntradayCard() {
		String gap = builder.priceMovePrompt(specGapCard());
		String intraday = builder.priceMovePrompt(specIntradayCard());

		assertThat(gap).isNotEqualTo(intraday);
		assertThat(gap).doesNotContain("같은 시간대 기사:");
		assertThat(intraday).doesNotContain("개장 전 기사:");
		assertThat(intraday).contains("구간: 11:20 ~ 11:25");
		// 갭 카드는 windowStart·windowEnd가 null인데도 조립이 깨지지 않아야 한다.
		assertThat(gap).doesNotContain("null");
	}

	@Test
	@DisplayName("갭 카드의 전날 기사에는 '전일'이 붙는다 — 없으면 모델이 당일로 읽는다")
	void openingGapCardMarksPreviousDaySources() {
		String gap = builder.priceMovePrompt(specGapCard());

		assertThat(gap).contains("(한국경제, 전일 22:40)");
		assertThat(gap).contains("(DART 공시, 전일 접수)");
	}

	// ---------- nullable 필드 생략 ----------

	@Test
	@DisplayName("매도 후 흐름·집단 비교·기사 간격·보유 구간 변동이 전부 없으면 그 줄들이 통째로 사라진다")
	void postSellPromptOmitsWholeLinesWhenNullableFieldsAreAbsent() {
		String expected = """
			종목: 삼성전자
			매수: 09:30, 70,000원 10주
			매도: 14:40, 68,500원 10주
			수익률: -2.17% (실현손익 -15,207원)

			보유 중 최고가: 11:05의 70,800원 (매도가가 3.25% 낮음)
			보유 중 최저가: 14:20의 68,100원 (매도가가 0.59% 높음)

			위 내용을 3~4문장으로 서술해줘. 수치를 그대로 나열하지 말고,
			매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘.""";

		String prompt = builder.postSellPrompt(minimalPostSell());

		// §C-5 게이트 통과 전의 매도 회고가 이 모양이다. 깨진 문장이나 null 리터럴이 남으면 모델이 그대로 서술한다.
		assertThat(prompt).isEqualTo(expected);
		assertThat(prompt).doesNotContain("null");
		assertThat(prompt).doesNotContain("매도 후 흐름");
		assertThat(prompt).doesNotContain("보유 구간에 걸친 변동");
		assertThat(prompt).doesNotContain("첫 근거 기사");
		assertThat(prompt).doesNotContain("같은 변동 구간을 겪은");
		// 줄이 빠지면서 빈 줄이 겹치면 모델 입력이 지저분해진다.
		assertThat(prompt).doesNotContain("\n\n\n");
	}

	@Test
	@DisplayName("집단 비교가 확정되면 그 줄이 붙는다 — 개인 식별값 없이 관측 수치만 들어간다")
	void postSellPromptAppendsPeerComparisonWhenSettled() {
		PostSellPromptDto withPeers = new PostSellPromptDto(
			"삼성전자", LocalTime.of(9, 30), bd("70000"), LocalTime.of(14, 40), bd("68500"), bd("10"),
			bd("-0.0217"), -15207L, bd("70800"), LocalTime.of(11, 5), bd("-0.0325"),
			bd("68100"), LocalTime.of(14, 20), bd("0.0059"), null, null, List.of(),
			bd("69200"), bd("0.0102"), 12, bd("0.25"), 45, 310);

		String prompt = builder.postSellPrompt(withPeers);

		assertThat(prompt).contains(
			"같은 변동 구간을 겪은 다른 사용자 12명 중 25.00%가 30분 내에 매도했고,"
				+ " 매도까지 걸린 시간의 중앙값은 45분입니다. 본인은 310분이었습니다.");
		assertThat(prompt).doesNotContain("null");
	}

	@Test
	@DisplayName("매수가 첫 근거 기사보다 늦으면 앞섰다가 아니라 지나 이뤄졌다로 갈린다")
	void buyToNewsLineFlipsWhenBuyHappensAfterTheFirstArticle() {
		PostSellPromptDto afterNews = withBuyToNews(-40, LocalTime.of(9, 10));

		String prompt = builder.postSellPrompt(afterNews);

		assertThat(prompt).contains("매수는 첫 근거 기사(09:10)가 나온 뒤 40분 지나 이뤄졌습니다.");
		assertThat(prompt).doesNotContain("앞섰습니다");
		// 음수 분이 그대로 새어 나가면 "-40분 지나"가 된다.
		assertThat(prompt).doesNotContain("-40");
	}

	@Test
	@DisplayName("매도 후 흐름이 매도가보다 낮으면 높음이 아니라 낮음으로 갈린다")
	void sellToCloseLineFlipsSignWord() {
		PostSellPromptDto belowSell = new PostSellPromptDto(
			"삼성전자", LocalTime.of(9, 30), bd("70000"), LocalTime.of(14, 40), bd("68500"), bd("10"),
			bd("-0.0217"), -15207L, bd("70800"), LocalTime.of(11, 5), bd("-0.0325"),
			bd("68100"), LocalTime.of(14, 20), bd("0.0059"), null, null, List.of(),
			bd("67800"), bd("-0.0102"), null, null, null, null);

		String prompt = builder.postSellPrompt(belowSell);

		assertThat(prompt).contains("매도 후 흐름: 마감 종가 67,800원 (매도가보다 1.02% 낮음)");
		// 부호는 단어로만 표현한다 — 숫자에 붙으면 "-1.02% 낮음"이 되어 뜻이 뒤집힌다.
		assertThat(prompt).doesNotContain("-1.02%");
	}

	// ---------- 픽스처 ----------

	private String sentenceRange(String prompt) {
		Matcher matcher = Pattern.compile("(\\d~\\d)문장").matcher(prompt);
		assertThat(matcher.find()).as("문장 수 지시가 없다: %s", prompt).isTrue();
		return matcher.group(1);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private PriceMovePromptDto specIntradayCard() {
		return new PriceMovePromptDto(
			"삼성전자", false, LocalTime.of(11, 20), LocalTime.of(11, 25), bd("-0.0182"), TRADING_DATE,
			List.of(
				article("삼성전자, 반도체 공장 가동 일시 중단", "한국경제", TRADING_DATE.atTime(11, 15)),
				article("반도체 업황 둔화 우려 확산", "매일경제", TRADING_DATE.atTime(11, 2))));
	}

	private PriceMovePromptDto specGapCard() {
		return new PriceMovePromptDto(
			"삼성전자", true, null, null, bd("0.0235"), TRADING_DATE,
			List.of(
				disclosure("삼성전자, 유상증자 결정", PREVIOUS_DATE.atStartOfDay()),
				article("미국 증시 반도체 업종 강세", "한국경제", PREVIOUS_DATE.atTime(22, 40))));
	}

	private PostSellPromptDto specPostSell() {
		return new PostSellPromptDto(
			"삼성전자", LocalTime.of(9, 30), bd("70000"), LocalTime.of(14, 40), bd("68500"), bd("10.00"),
			bd("-0.0217"), -15207L, bd("70800"), LocalTime.of(11, 5), bd("-0.0325"),
			bd("68100"), LocalTime.of(14, 20), bd("0.0059"), 105, LocalTime.of(11, 15),
			List.of(new HeldPriceMoveDto(
				LocalTime.of(11, 20), LocalTime.of(11, 25), bd("-0.0182"), 115, 195,
				List.of(article("삼성전자 반도체 공장 가동 일시 중단", "한국경제", TRADING_DATE.atTime(11, 15))))),
			bd("69200"), bd("0.0102"), null, null, null, null);
	}

	private PostSellPromptDto minimalPostSell() {
		return new PostSellPromptDto(
			"삼성전자", LocalTime.of(9, 30), bd("70000"), LocalTime.of(14, 40), bd("68500"), bd("10"),
			bd("-0.0217"), -15207L, bd("70800"), LocalTime.of(11, 5), bd("-0.0325"),
			bd("68100"), LocalTime.of(14, 20), bd("0.0059"), null, null, List.of(),
			null, null, null, null, null, null);
	}

	private PostSellPromptDto withBuyToNews(int minutes, LocalTime firstNewsAt) {
		return new PostSellPromptDto(
			"삼성전자", LocalTime.of(9, 30), bd("70000"), LocalTime.of(14, 40), bd("68500"), bd("10"),
			bd("-0.0217"), -15207L, bd("70800"), LocalTime.of(11, 5), bd("-0.0325"),
			bd("68100"), LocalTime.of(14, 20), bd("0.0059"), minutes, firstNewsAt, List.of(),
			null, null, null, null, null, null);
	}

	private NewsSummaryPromptDto specNewsSummary(NewsSummaryScope scope) {
		return new NewsSummaryPromptDto(
			"삼성전자", scope, TRADING_DATE,
			List.of(
				article("반도체 업황 둔화 우려 확산", "매일경제", PREVIOUS_DATE.atTime(18, 40)),
				disclosure("삼성전자, 유상증자 결정", PREVIOUS_DATE.atStartOfDay())));
	}

	private MarketBriefingPromptDto specBriefing(Market market) {
		return new MarketBriefingPromptDto(
			market, TRADING_DATE,
			List.of(
				new BriefingNewsItemDto(
					"삼성전자", article("반도체 업황 둔화 우려 확산", "매일경제", PREVIOUS_DATE.atTime(18, 40))),
				new BriefingNewsItemDto(
					"SK하이닉스", article("미국 증시 반도체 업종 강세", "한국경제", TRADING_DATE.atTime(6, 20))),
				new BriefingNewsItemDto(
					"LG화학", disclosure("주요사항보고서 유상증자결정", PREVIOUS_DATE.atStartOfDay()))));
	}

	private NewsSourceDto article(String title, String publisher, LocalDateTime publishedAt) {
		return new NewsSourceDto(title, publisher, publishedAt, false);
	}

	private NewsSourceDto disclosure(String title, LocalDateTime receivedAt) {
		return new NewsSourceDto(title, "DART", receivedAt, true);
	}
}
