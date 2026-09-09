package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class NarrativeTemplateBuilder {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
	private static final int PERCENT_SCALE = 2;
	private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);

	public String priceMoveTemplate(PriceMovePromptDto input) {
		if (input.openingGap()) {
			return "직전 거래일 종가 대비 %s %s 시작했습니다. 개장 전 뉴스·공시 %d건이 있었습니다.".formatted(
				absolutePercent(input.changeRate()),
				input.changeRate().signum() < 0 ? "낮게" : "높게",
				input.sources().size());
		}
		return "%s부터 %d분간 %s %s했습니다. 같은 시간대에 기사 %d건이 있었습니다.".formatted(
			input.windowStart().format(TIME),
			input.windowMinutes(),
			absolutePercent(input.changeRate()),
			input.changeRate().signum() < 0 ? "하락" : "상승",
			input.sources().size());
	}

	public String postSellTemplate(PostSellPromptDto input) {
		String base = "%s에 매수해 %s에 매도했습니다. 수익률은 %s입니다.".formatted(
			money(input.buyPrice()), money(input.sellPrice()), signedPercent(input.returnRate()));
		if (input.holdHighPrice() == null || input.holdHighAt() == null) {
			return base;
		}
		String moment = input.holdHighBasis() == HoldHighBasis.DAILY
			? input.holdHighAt().format(DATE) + " 종가"
			: input.holdHighAt().format(input.multiDayHold() ? DATE_TIME : TIME);
		return base + " 보유 중 최고가는 %s의 %s이었습니다.".formatted(moment, money(input.holdHighPrice()));
	}

	private String absolutePercent(BigDecimal rate) {
		return toPercent(rate).abs().toPlainString() + "%";
	}

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
