// 파트별 LLM 프롬프트 문자열을 조립한다 — 시스템 1종 + 사용자 4종 + 재생성 1종.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.HoldHighBasis;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 프롬프트를 {@code NarrativeGenerator} 밖에 두는 이유는 프로바이더를 바꿔도 프롬프트가 딸려 가지 않게
 * 하려는 것이다 (spec §C-6, ADR-0011). 이 클래스는 외부 의존이 전혀 없어 조립된 문자열을 단위 테스트로
 * 그대로 단정할 수 있다.
 *
 * <p>아래 문자열의 시각 리터럴(15:30·09:00)은 §C-2 참조 규칙의 <b>의도된 예외</b>다 — 모델에게 주는
 * 자연어라 값을 그대로 적어야 한다(spec §LLM 프롬프트). 다만 예외는 여기까지다. 임계값·크론·상한처럼
 * 판단 기준이 되는 값은 프롬프트에 박지 않고 호출부가 §C-7 프로퍼티로 처리한다.
 */
@Component
public class NarrativePromptBuilder {

	// 네 파트 공통. 마지막 줄이 요약·브리핑에서 특히 중요하다 — 경제 기사 제목에 `전망`이 흔한데
	// 모델이 제목을 인용하면 §후검증에 걸려 요약이 통째로 폐기된다 (spec §후검증).
	private static final String SYSTEM_PROMPT = """
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

	// 적발 사유를 지정하지 않고 걸린 표현 자체를 넘긴다 — 사유가 제목 인용이 아닐 수도 있다(spec §후검증).
	// 자리표시자를 String.format이 아니라 replace로 채우는 이유는 SpotBugs VA_FORMAT_STRING_USES_NEWLINE이다 —
	// 여러 줄 format 문자열에 %n을 쓰라고 요구하는데, %n은 플랫폼별로 CRLF가 되어 프롬프트가 OS에 따라 달라진다.
	private static final String DETECTED_PLACEHOLDER = "{적발된 표현들}";
	private static final String REGENERATION_TEMPLATE = """
		직전 출력이 아래 금지 표현에 걸려 폐기됐다: {적발된 표현들}

		같은 표현을 쓰지 말고 다시 써라. 기사 제목을 그대로 인용하지 마라 —
		제목에 든 전망·기대·예측 표현이 그대로 따라 들어온다.
		"업황 전망을 다룬 기사"처럼 쓰지 말고 "업황을 다룬 기사"처럼 써라.""";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	// 하루를 넘긴 보유에서만 쓴다 — 연도는 붙이지 않는다. 회고는 언제나 최근 매매라 연도가 문장을 늘릴 뿐이다.
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
	private static final String STOCK_MARKET_LABEL = "국내 주식";
	private static final String CRYPTO_MARKET_LABEL = "코인";
	private static final int PERCENT_SCALE = 2;
	private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);

	public String systemPrompt() {
		return SYSTEM_PROMPT;
	}

	// 변동 원인 카드 — 문장 수 지시는 2~3문장이다 (spec §LLM 프롬프트 마지막 줄).
	public String priceMovePrompt(PriceMovePromptDto input) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("종목: ").append(input.instrumentName()).append('\n');
		if (input.openingGap()) {
			// 갭 카드는 구간이 없고 근거가 전장 기사다. 장중 카드와 같은 "구간 11:20 ~ 11:25" 줄을 쓰면
			// 모델이 없는 구간을 서술하게 된다 (§템플릿 문장도 두 카드를 따로 둔다).
			prompt.append("구간: 개장 시가 (직전 거래일 종가 대비)\n");
		} else {
			prompt.append("구간: ")
				.append(input.windowStart().format(TIME))
				.append(" ~ ")
				.append(input.windowEnd().format(TIME))
				.append('\n');
		}
		prompt.append("변동률: ").append(signedPercent(input.changeRate())).append("\n\n");
		prompt.append(input.openingGap() ? "개장 전 기사:\n" : "같은 시간대 기사:\n");
		for (NewsSourceDto source : input.sources()) {
			prompt.append("- ").append(sourceLine(source, input.referenceDate(), false)).append('\n');
		}
		prompt.append("\n위 내용을 2~3문장으로 서술해줘.");
		return prompt.toString();
	}

	// 매도 회고 — 3~4문장. 수치 나열이 아니라 매수·매도가 변동·기사와 어떤 순서였는지를 앞세우도록 지시한다.
	public String postSellPrompt(PostSellPromptDto input) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("종목: ").append(input.instrumentName()).append('\n');
		prompt.append("매수: ")
			.append(holdMoment(input.buyAt(), input.multiDayHold()))
			.append(", ")
			.append(money(input.buyPrice()))
			.append(' ')
			.append(quantity(input.quantity()))
			.append('\n');
		prompt.append("매도: ")
			.append(holdMoment(input.sellAt(), input.multiDayHold()))
			.append(", ")
			.append(money(input.sellPrice()))
			.append(' ')
			.append(quantity(input.quantity()))
			.append('\n');
		prompt.append("수익률: ")
			.append(signedPercent(input.returnRate()))
			.append(" (실현손익 ")
			.append(money(BigDecimal.valueOf(input.realizedPnl())))
			.append(")\n");

		// 보유 구간 극값은 sameSessionCompleted=false(여러 재생일에 걸친 매매)면 그룹 전체가 null이다
		// (spec §파생 사실 계산). 분봉이 불연속이라 계산 자체가 성립하지 않는다. 매도 후 흐름·집단 비교와
		// 같은 방식으로 줄을 통째로 생략한다 — 여기서 역참조하면 NPE가 생성기 밖에서 터져 Optional 폴백에
		// 걸리지 않고, 매도 회고 조회가 500이 되어 "narrativeStatus는 항상 READY"가 깨진다.
		//
		// 파생 사실 세 줄을 따로 모으는 이유는 앞의 빈 줄 때문이다. 세 줄이 전부 빠지는 경우가 실재하는데
		// (극값 없음 + 근거 기사 없음) 구분용 빈 줄을 미리 찍어 두면 빈 줄이 둘 연달아 남는다.
		StringBuilder derivedFacts = new StringBuilder();
		if (input.holdHighPrice() != null) {
			derivedFacts.append("보유 중 최고가: ")
				.append(extremeMoment(input.holdHighAt(), input))
				.append("의 ")
				.append(money(input.holdHighPrice()))
				.append(" (")
				.append(sellVersus(input.sellVsHighRate()))
				.append(")\n");
		}
		if (input.holdLowPrice() != null) {
			derivedFacts.append("보유 중 최저가: ")
				.append(extremeMoment(input.holdLowAt(), input))
				.append("의 ")
				.append(money(input.holdLowPrice()))
				.append(" (")
				.append(sellVersus(input.sellVsLowRate()))
				.append(")\n");
		}
		if (input.buyToNewsMinutes() != null) {
			derivedFacts.append(buyToNewsLine(input)).append('\n');
		}
		if (!derivedFacts.isEmpty()) {
			prompt.append('\n').append(derivedFacts);
		}

		if (!input.priceMoves().isEmpty()) {
			prompt.append("\n보유 구간에 걸친 변동:\n");
			for (HeldPriceMoveDto move : input.priceMoves()) {
				prompt.append("- ")
					.append(holdMoment(move.windowStart(), input.multiDayHold()))
					.append('~')
					.append(holdMoment(move.windowEnd(), input.multiDayHold()))
					.append(' ')
					.append(signedPercent(move.changeRate()))
					.append(" (매수 ")
					.append(move.minutesAfterBuy())
					.append("분 뒤, 매도 ")
					.append(move.minutesBeforeSell())
					.append("분 전)\n");
				for (NewsSourceDto source : move.sources()) {
					prompt.append("  근거: ").append(sourceLine(source, null, false)).append('\n');
				}
			}
		}

		// 매도 후 흐름과 집단 비교는 §C-5의 게이트를 통과한 뒤에만 값이 있다. 그 전에는 줄 자체가 빠진다.
		if (input.closePrice() != null) {
			prompt.append("\n매도 후 흐름: 마감 종가 ")
				.append(money(input.closePrice()))
				.append(" (매도가보다 ")
				.append(absolutePercent(input.sellToCloseRate()))
				.append(input.sellToCloseRate().signum() < 0 ? " 낮음" : " 높음")
				.append(")\n");
		}
		if (input.holderCount() != null) {
			prompt.append('\n').append(peerLine(input)).append('\n');
		}

		prompt.append("\n위 내용을 3~4문장으로 서술해줘. 수치를 그대로 나열하지 말고,\n")
			.append("매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘.");
		return prompt.toString();
	}

	/**
	 * 매수·매도 시각을 문장에 넣는 형태 — 하루를 넘긴 보유면 날짜를 붙인다 (이슈 #275).
	 *
	 * <p>코인은 §FEED-012 결정 4의 일봉 경로가 <b>정의상 199분 초과 보유</b>라 대부분 날짜를 넘긴다. 시·분만
	 * 주면 8/1 14:20 매수 → 8/5 09:05 매도가 모델에게 "14:20 매수, 09:05 매도"로 보여 <b>매도가 매수보다
	 * 이르다는 문장</b>이 나온다. <b>주식은 {@code multiDayHold}가 언제나 거짓이라 출력이 그대로다.</b>
	 */
	private static String holdMoment(LocalDateTime at, boolean multiDayHold) {
		return multiDayHold ? at.format(DATE_TIME) : at.format(TIME);
	}

	/**
	 * 보유 구간 극값의 시각 — 일봉 표본으로 잰 값이면 시·분을 적지 않는다 (§FEED-012 결정 4).
	 *
	 * <p>{@link HoldHighBasis#DAILY}일 때 응답의 극값 시각은 그 일자의 <b>일봉 라벨</b>({@code 23:59})이지
	 * 가격을 실제로 잰 시각이 아니다. 그대로 문장에 넣으면 "23시 59분의 1,000원"처럼 <b>재지 않은 시각을
	 * 단정</b>하게 되므로 "그 일자의 종가"라고 적는다. 주식은 언제나 {@link HoldHighBasis#MINUTE}다.
	 */
	private static String extremeMoment(LocalDateTime at, PostSellPromptDto input) {
		if (input.holdHighBasis() == HoldHighBasis.DAILY) {
			return at.format(DATE) + " 종가";
		}
		return holdMoment(at, input.multiDayHold());
	}

	// 종목 뉴스 요약 — 3~5문장. 범위 줄이 PRE_MARKET·FULL·ROLLING_24H로 갈린다.
	public String newsSummaryPrompt(NewsSummaryPromptDto input) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("종목: ").append(input.instrumentName()).append('\n');
		prompt.append("범위: ").append(input.scope().promptText()).append("\n\n");
		prompt.append("기사:\n");
		for (NewsSourceDto item : input.items()) {
			prompt.append("- ").append(sourceLine(item, input.referenceDate(), true)).append('\n');
		}
		prompt.append("\n위 기사들을 종합해 3~5문장으로 서술해줘.\n")
			.append("특정 기사의 문장을 그대로 옮기지 말고, 무엇을 다룬 기사들인지 써줘.");
		return prompt.toString();
	}

	// 개장 전 브리핑 — 3~6문장. 전 종목이 대상이라 기사마다 종목명을 붙인다.
	public String marketBriefingPrompt(MarketBriefingPromptDto input) {
		boolean stock = input.market() == Market.STOCK;
		StringBuilder prompt = new StringBuilder();
		prompt.append("시장: ").append(stock ? STOCK_MARKET_LABEL : CRYPTO_MARKET_LABEL).append('\n');
		prompt.append("범위: ")
			.append(stock ? NewsSummaryScope.PRE_MARKET.promptText() : NewsSummaryScope.ROLLING_24H.promptText())
			.append("\n\n");
		prompt.append("기사:\n");
		for (BriefingNewsItemDto item : input.items()) {
			prompt.append("- [")
				.append(item.instrumentName())
				.append("] ")
				.append(sourceLine(item.source(), input.referenceDate(), true))
				.append('\n');
		}
		prompt.append("\n위 내용을 3~6문장으로 서술해줘.\n")
			.append("종목명을 언급해도 되지만 사거나 팔라고 하지 마라.\n")
			.append("어떤 종목에 어떤 소식이 있었는지만 써줘.");
		return prompt.toString();
	}

	/**
	 * 재생성 프롬프트. 요약·브리핑 전용이며 1회만 쓴다 (spec §후검증).
	 *
	 * <p>1차 사용자 프롬프트를 앞에 그대로 붙인다 — 기사 목록이 빠지면 2차 생성에 쓸 재료가 없다.
	 * 적발 표현은 요약하거나 개수만 넣지 않고 <b>그대로</b> 넣는다. 무엇에 걸렸는지 알려주지 않으면
	 * 2차도 같은 단어를 쓰고, 요약에는 템플릿이 없어 곧바로 {@code NONE}이 된다.
	 */
	public String regenerationPrompt(String originalUserPrompt, List<String> detectedExpressions) {
		String joined = String.join(", ", detectedExpressions);
		return originalUserPrompt + "\n\n" + REGENERATION_TEMPLATE.replace(DETECTED_PLACEHOLDER, joined);
	}

	// 기사 1줄. 카드는 근거가 같은 날 장중이라 시각만 적고(spec 예시), 요약·브리핑은 전장 구간을 다뤄
	// 하루 경계를 넘으므로 "전일"·"당일"을 항상 붙인다. 카드도 전날 기사면 "전일"을 붙인다 — 갭 카드의
	// 근거는 정의상 전장 기사라 표시가 없으면 모델이 당일로 읽는다.
	private String sourceLine(NewsSourceDto source, LocalDate referenceDate, boolean alwaysMarkDay) {
		String dayMark = dayMark(source.publishedAt(), referenceDate, alwaysMarkDay);
		if (source.disclosure()) {
			// 공시는 접수일자만 있어 시각이 항상 00:00:00이다 (§C-3). 시각 대신 "접수"로 적는다.
			return "%s (DART 공시, %s접수)".formatted(source.title(), dayMark);
		}
		return "%s (%s, %s%s)".formatted(
			source.title(), source.publisher(), dayMark, source.publishedAt().toLocalTime().format(TIME));
	}

	private String dayMark(LocalDateTime publishedAt, LocalDate referenceDate, boolean alwaysMarkDay) {
		if (referenceDate == null) {
			return "";
		}
		if (publishedAt.toLocalDate().isBefore(referenceDate)) {
			return "전일 ";
		}
		return alwaysMarkDay ? "당일 " : "";
	}

	private String buyToNewsLine(PostSellPromptDto input) {
		int minutes = input.buyToNewsMinutes();
		String at = holdMoment(input.firstNewsAt(), input.multiDayHold());
		if (minutes >= 0) {
			return "매수는 첫 근거 기사(%s)보다 %d분 앞섰습니다.".formatted(at, minutes);
		}
		return "매수는 첫 근거 기사(%s)가 나온 뒤 %d분 지나 이뤄졌습니다.".formatted(at, -minutes);
	}

	// 집단 비교는 관측된 사실이라 서술에 넣어도 된다 (FEED-011). 개인 식별값은 어떤 형태로도 넣지 않는다.
	private String peerLine(PostSellPromptDto input) {
		// 줄바꿈을 format 문자열에 넣지 않는다 — SpotBugs VA_FORMAT_STRING_USES_NEWLINE이 %n을 요구하는데
		// %n은 플랫폼별로 CRLF가 되어 프롬프트가 OS에 따라 달라진다. 개행은 호출부가 붙인다.
		return "같은 변동 구간을 겪은 다른 사용자 %d명 중 %s가 30분 내에 매도했고, 매도까지 걸린 시간의 중앙값은 %d분입니다. 본인은 %d분이었습니다."
			.formatted(
				input.holderCount(),
				absolutePercent(input.soldWithin30MinRate()),
				input.medianMinutesToSell(),
				input.yourMinutesToSell());
	}

	private String sellVersus(BigDecimal rate) {
		return "매도가가 %s %s".formatted(absolutePercent(rate), rate.signum() < 0 ? "낮음" : "높음");
	}

	private String signedPercent(BigDecimal rate) {
		BigDecimal percent = toPercent(rate);
		return (percent.signum() < 0 ? "" : "+") + percent.toPlainString() + "%";
	}

	private String absolutePercent(BigDecimal rate) {
		return toPercent(rate).abs().toPlainString() + "%";
	}

	private BigDecimal toPercent(BigDecimal rate) {
		return rate.multiply(PERCENT_MULTIPLIER).setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
	}

	private String money(BigDecimal value) {
		return String.format(Locale.KOREA, "%,d원", value.setScale(0, RoundingMode.HALF_UP).longValueExact());
	}

	/**
	 * 수량 — <b>단위를 붙이지 않는다</b>(PR #281 리뷰).
	 *
	 * <p>2차까지는 매도 회고가 주식 전용이라 "주"를 하드코딩했는데, 이슈 #275로 코인이 들어오면서
	 * {@code 0.0025주}가 나왔다. 시장별로 "주"·"개"를 가르려면 이 클래스가 시장을 알아야 하는데,
	 * {@code PostSellFeedbackService}는 <b>의도적으로 시장을 모르고</b>(시장별 조립은 리더가 가른다)
	 * 시장을 넘기려면 응답 계약에 필드를 더해야 한다. 단위 하나를 위해 계약을 넓히지 않고 <b>수량만
	 * 적는 쪽</b>을 택했다 — 종목명이 이미 줄에 있어 모델이 단위를 오해할 자리가 아니다.
	 *
	 * <p>원장의 수량은 {@code BigDecimal}이라 정수도 {@code "10.00"}으로 들어온다. 불필요한 0을 떼어
	 * {@code "10"}·{@code "0.0025"}로 적는다.
	 */
	private String quantity(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}
