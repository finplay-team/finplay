package com.finplay.api.domain.feedback.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NarrativeValidator {

	private static final List<String> CAUSATION = List.of("때문에", "영향으로", "여파로", "덕분에", "로 인해", "탓에");

	private static final List<String> RECOMMENDATION = List.of("매수하세요", "매도하세요", "사야", "팔아야", "추천", "주목할", "유망",
		"비중 확대");

	private static final List<String> PREDICTION = List.of("오를 것", "내릴 것", "전망", "예상됩니다", "기대됩니다", "상승할 것", "하락할 것");

	private static final List<String> ADVICE = List.of("하세요", "했으면", "좋았을", "아쉽", "권장");

	private static final List<String> JUDGEMENT = List.of("버티", "놓치", "실수", "잘못", "다행", "기회를", "았다면", "었다면", "였다면",
		"했다면", "렸다면");

	private static final List<String> WITHOUT_JUDGEMENT = Stream.of(CAUSATION, RECOMMENDATION, PREDICTION, ADVICE)
		.flatMap(List::stream).toList();

	private static final List<String> ALL_RULES = Stream.of(WITHOUT_JUDGEMENT, JUDGEMENT).flatMap(List::stream)
		.toList();

	public NarrativeValidationDto validateCardOrPostSell(String narrative) {
		return detect(narrative, ALL_RULES);
	}

	public NarrativeValidationDto validateSummaryOrBriefing(String narrative) {
		return detect(narrative, WITHOUT_JUDGEMENT);
	}

	private NarrativeValidationDto detect(String narrative, List<String> rules) {
		if (!StringUtils.hasText(narrative)) {
			return new NarrativeValidationDto(List.of());
		}
		List<String> detected = new ArrayList<>();
		for (String expression : rules) {
			if (narrative.contains(expression)) {
				detected.add(expression);
			}
		}
		return new NarrativeValidationDto(detected);
	}
}
