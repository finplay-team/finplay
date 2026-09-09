package com.finplay.api.domain.feedback.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class NarrativeNumberValidator {

	private static final Pattern NUMBER = Pattern.compile("[+\\-]?\\d+(?:,\\d{3})*(?:\\.\\d+)?");

	public NarrativeValidationDto validate(String narrative, String prompt) {
		if (!StringUtils.hasText(narrative)) {
			return new NarrativeValidationDto(List.of());
		}
		List<BigDecimal> allowed = numbersIn(prompt);

		List<String> detected = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Matcher matcher = NUMBER.matcher(narrative);
		while (matcher.find()) {
			String token = matcher.group();
			if (seen.add(token) && !sourced(token, allowed)) {
				detected.add(token);
			}
		}
		return new NarrativeValidationDto(detected);
	}

	private List<BigDecimal> numbersIn(String prompt) {
		if (!StringUtils.hasText(prompt)) {
			return List.of();
		}
		List<BigDecimal> numbers = new ArrayList<>();
		Matcher matcher = NUMBER.matcher(prompt);
		while (matcher.find()) {
			numbers.add(normalize(matcher.group()));
		}
		return numbers;
	}

	private boolean sourced(String token, List<BigDecimal> allowed) {
		BigDecimal value = normalize(token);
		boolean explicitSign = token.startsWith("+") || token.startsWith("-");
		for (BigDecimal candidate : allowed) {
			boolean same = explicitSign
				? candidate.compareTo(value) == 0
				: candidate.abs().compareTo(value.abs()) == 0;
			if (same) {
				return true;
			}
		}
		return false;
	}

	private BigDecimal normalize(String token) {
		return new BigDecimal(token.replace(",", ""));
	}
}
