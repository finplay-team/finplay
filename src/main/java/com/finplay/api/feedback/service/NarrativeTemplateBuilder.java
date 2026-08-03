// LLM이 실패하거나 후검증에 걸렸을 때 쓸 서술을 서버가 수치로 조립한다 — 장중 카드·시가 갭·매도 회고 3종.
package com.finplay.api.feedback.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * <b>문장을 만들기만 한다.</b> 언제 템플릿으로 갈아탈지 판정하는 것은 {@code NarrativeService}이며, 그래서
 * 이 클래스는 {@code NarrativeGenerator}·{@code NarrativeValidator}를 주입받지 않는다 (spec §C-6).
 *
 * <p>요약·브리핑에는 템플릿이 없다 — 여러 기사를 종합하는 것이 요약의 본질이라 수치 조립으로 대체할 수 없다.
 * 걸리면 {@code summary=null}·{@code NarrativeSource.NONE}이 된다 (§템플릿 문장).
 *
 * <p>조립된 문장 자체가 §후검증을 통과해야 한다. 폴백 문장이 금지 표현에 걸리면 폴백이 성립하지 않는다.
 */
@Component
public class NarrativeTemplateBuilder {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final int PERCENT_SCALE = 2;
	private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);

	/**
	 * 변동 원인 카드. {@code openingGap}에 따라 §템플릿 문장 표의 장중 카드 / 시가 갭 두 문장으로 갈린다.
	 * 갈래를 호출부가 고르지 않게 두는 이유는 {@code NarrativePromptBuilder.priceMovePrompt}와 같다 —
	 * 같은 입력에서 프롬프트와 폴백 문장이 서로 다른 카드 종류로 조립되는 사고를 막는다.
	 */
	public String priceMoveTemplate(PriceMovePromptDto input) {
		if (input.openingGap()) {
			return "직전 거래일 종가 대비 %s %s 시작했습니다. 개장 전 뉴스·공시 %d건이 있었습니다.".formatted(
				absolutePercent(input.changeRate()),
				input.changeRate().signum() < 0 ? "낮게" : "높게",
				input.sources().size());
		}
		long windowMinutes = ChronoUnit.MINUTES.between(input.windowStart(), input.windowEnd());
		return "%s부터 %d분간 %s %s했습니다. 같은 시간대에 기사 %d건이 있었습니다.".formatted(
			input.windowStart().format(TIME),
			windowMinutes,
			absolutePercent(input.changeRate()),
			input.changeRate().signum() < 0 ? "하락" : "상승",
			input.sources().size());
	}

	/**
	 * 매도 회고. 매도 회고에는 이 템플릿이 있어 {@code narrativeStatus}가 항상 {@code READY}다 — LLM이
	 * 실패해도 서술이 비는 경우가 없다 (api-contracts 매도 직후 피드백).
	 *
	 * <p>보유 구간 극값이 없으면 마지막 문장을 뺀다. 여러 재생일에 걸친 매매는 분봉이 불연속이라 극값 계산이
	 * 성립하지 않아 {@code holdHighPrice}가 {@code null}인데(§파생 사실 계산), 원장 수치로 만드는 앞 두 문장은
	 * 그때도 그대로 성립한다. 없는 값을 지어내지 않고 그 문장만 빼는 쪽을 택했다.
	 */
	public String postSellTemplate(PostSellPromptDto input) {
		String base = "%s에 매수해 %s에 매도했습니다. 수익률은 %s입니다.".formatted(
			money(input.buyPrice()), money(input.sellPrice()), signedPercent(input.returnRate()));
		if (input.holdHighPrice() == null || input.holdHighAt() == null) {
			return base;
		}
		return base + " 보유 중 최고가는 %s의 %s이었습니다.".formatted(
			input.holdHighAt().format(TIME), money(input.holdHighPrice()));
	}

	// 아래 세 포맷 도우미는 NarrativePromptBuilder에도 같은 형태로 있다. 지금은 중복을 그대로 둔다 —
	// 공통화는 세 번째 중복이 보일 때 검토한다(docs/conventions.md)는 기준이 하나이고, 이미 골든 마스터
	// 테스트가 붙은 클래스를 이 항목의 범위 밖에서 건드리지 않으려는 것이 다른 하나다. 셋째 사용처가
	// 생기면 그때 한 클래스로 뽑는다.

	// 방향 단어(상승·하락, 높게·낮게)가 부호를 이미 나타내므로 숫자는 절댓값으로 적는다.
	private String absolutePercent(BigDecimal rate) {
		return toPercent(rate).abs().toPlainString() + "%";
	}

	// 수익률에는 방향 단어가 없으므로 부호를 그대로 적는다.
	private String signedPercent(BigDecimal rate) {
		BigDecimal percent = toPercent(rate);
		return (percent.signum() < 0 ? "" : "+") + percent.toPlainString() + "%";
	}

	private BigDecimal toPercent(BigDecimal rate) {
		return rate.multiply(PERCENT_MULTIPLIER).setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
	}

	private String money(BigDecimal value) {
		return String.format(Locale.KOREA, "%,d원", value.setScale(0, RoundingMode.HALF_UP).longValueExact());
	}
}
