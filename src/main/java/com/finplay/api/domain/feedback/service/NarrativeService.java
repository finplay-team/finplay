package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NarrativeService {

	private final NarrativeGenerator generator;

	private final NarrativePromptBuilder promptBuilder;

	private final NarrativeValidator validator;

	private final NarrativeNumberValidator numberValidator;

	private final NarrativeTemplateBuilder templateBuilder;

	private final FeedbackLlmProperties properties;

	public NarrativeResultDto resolvePriceMoveNarrative(PriceMovePromptDto input) {
		return resolveWithTemplateFallback(
			"변동 카드", promptBuilder.priceMovePrompt(input), templateBuilder.priceMoveTemplate(input), null);
	}

	public NarrativeResultDto resolvePostSellNarrative(PostSellPromptDto input) {
		String userPrompt = promptBuilder.postSellPrompt(input);
		return resolveWithTemplateFallback(
			"매도 회고", userPrompt, templateBuilder.postSellTemplate(input), userPrompt);
	}

	public NarrativeResultDto resolveNewsSummaryNarrative(NewsSummaryPromptDto input) {
		return resolveWithRegeneration("뉴스 요약", promptBuilder.newsSummaryPrompt(input));
	}

	public NarrativeResultDto resolveMarketBriefingNarrative(MarketBriefingPromptDto input) {
		return resolveWithRegeneration("개장 전 브리핑", promptBuilder.marketBriefingPrompt(input));
	}

	private NarrativeResultDto resolveWithTemplateFallback(
		String part, String userPrompt, String template, String numberSourcePrompt) {
		Optional<String> generated = generator.generate(promptBuilder.systemPrompt(), userPrompt);
		if (generated.isPresent()) {
			List<String> detected = detect(generated.get(), numberSourcePrompt);
			if (detected.isEmpty()) {
				return NarrativeResultDto.llm(generated.get());
			}
			log.info("{} 서술이 후검증에 걸려 템플릿으로 대체한다. 적발={}", part, detected);
		} else {
			log.debug("{} 서술 생성이 실패해 템플릿으로 대체한다.", part);
		}
		return NarrativeResultDto.template(template);
	}

	private List<String> detect(String narrative, String numberSourcePrompt) {
		List<String> detected = new ArrayList<>(validator.validateCardOrPostSell(narrative).detectedExpressions());
		if (numberSourcePrompt != null) {
			detected.addAll(numberValidator.validate(narrative, numberSourcePrompt).detectedExpressions());
		}
		return detected;
	}

	private NarrativeResultDto resolveWithRegeneration(String part, String userPrompt) {
		String systemPrompt = promptBuilder.systemPrompt();
		String currentPrompt = userPrompt;
		int maxRegeneration = properties.maxRegeneration();

		for (int attempt = 0; attempt <= maxRegeneration; attempt++) {
			Optional<String> generated = generator.generate(systemPrompt, currentPrompt);
			if (generated.isEmpty()) {
				log.debug("{} 서술 생성이 실패했다. 템플릿이 없어 서술 없이 둔다.", part);
				return NarrativeResultDto.none();
			}
			NarrativeValidationDto validation = validator.validateSummaryOrBriefing(generated.get());
			if (validation.passed()) {
				return NarrativeResultDto.llm(generated.get());
			}
			if (attempt == maxRegeneration) {
				log.info("{} 서술이 재생성 {}회 후에도 후검증에 걸려 서술 없이 둔다. 적발={}",
					part, maxRegeneration, validation.detectedExpressions());
				break;
			}
			currentPrompt = promptBuilder.regenerationPrompt(userPrompt, validation.detectedExpressions());
			log.info("{} 서술이 후검증에 걸려 재생성한다. 적발={}", part, validation.detectedExpressions());
		}
		return NarrativeResultDto.none();
	}
}
