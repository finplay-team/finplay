// 파트별 서술을 확정하는 이 도메인의 유일한 진입점 — 생성 → 후검증 → 폴백(템플릿 또는 재생성)을 실행한다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackLlmProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 뒤 이슈의 조회·배치 서비스는 이 서비스 하나만 주입하면 되고 생성기·검증기·프롬프트·템플릿을 직접 알 필요가
 * 없다 (spec §C-6).
 *
 * <p>파트에 따라 흐름이 둘로 갈린다.
 *
 * <ul>
 * <li><b>카드·매도 회고 (1단계)</b> — 생성 → 통과면 {@code LLM}, 걸리거나 호출이 실패하면 곧바로 템플릿.
 * 재시도하지 않는다. 대체할 문장이 이미 있으므로 프로바이더를 다시 부를 이유가 없다.</li>
 * <li><b>요약·브리핑 (2단계)</b> — 걸리면 적발 표현을 넣어 {@code max-regeneration}회만 재생성하고, 그래도
 * 걸리면 서술 없음 + {@code NONE}. 기사 제목에 `전망` 같은 금지어가 흔해 재생성 경로가 없으면 요약 기능이
 * 통째로 사라진다.</li>
 * </ul>
 *
 * <p>이 경로를 {@code NarrativeValidator}에 두지 않은 이유는 §C-6에 있다 — 템플릿 폴백은 이미 만들어 둔
 * 문장을 고르는 국소적 동작이지만 재생성은 프로바이더를 다시 부르는 다른 층위다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NarrativeService {

	private final NarrativeGenerator generator;

	private final NarrativePromptBuilder promptBuilder;

	private final NarrativeValidator validator;

	private final NarrativeTemplateBuilder templateBuilder;

	private final FeedbackLlmProperties properties;

	/** 변동 원인 카드 (1단계). 실패·적발 시 §템플릿 문장의 장중 카드 또는 시가 갭 문장으로 대체된다. */
	public NarrativeResultDto resolvePriceMoveNarrative(PriceMovePromptDto input) {
		return resolveWithTemplateFallback(
			"변동 카드", promptBuilder.priceMovePrompt(input), templateBuilder.priceMoveTemplate(input));
	}

	/** 매도 직후 회고 (1단계). {@code narrativeStatus}가 항상 {@code READY}인 근거가 이 폴백이다. */
	public NarrativeResultDto resolvePostSellNarrative(PostSellPromptDto input) {
		return resolveWithTemplateFallback(
			"매도 회고", promptBuilder.postSellPrompt(input), templateBuilder.postSellTemplate(input));
	}

	/** 종목 뉴스 요약 (2단계). 템플릿이 없어 끝까지 걸리면 {@code NONE}이다. */
	public NarrativeResultDto resolveNewsSummaryNarrative(NewsSummaryPromptDto input) {
		return resolveWithRegeneration("뉴스 요약", promptBuilder.newsSummaryPrompt(input));
	}

	/** 개장 전 브리핑 (2단계). 규칙은 뉴스 요약과 같다. */
	public NarrativeResultDto resolveMarketBriefingNarrative(MarketBriefingPromptDto input) {
		return resolveWithRegeneration("개장 전 브리핑", promptBuilder.marketBriefingPrompt(input));
	}

	// 생성 실패와 후검증 적발이 같은 분기로 수렴한다 — 호출부 입장에서 둘 다 "LLM 문장을 쓸 수 없다"로 같다.
	private NarrativeResultDto resolveWithTemplateFallback(String part, String userPrompt, String template) {
		Optional<String> generated = generator.generate(promptBuilder.systemPrompt(), userPrompt);
		if (generated.isPresent()) {
			NarrativeValidationDto validation = validator.validateCardOrPostSell(generated.get());
			if (validation.passed()) {
				return NarrativeResultDto.llm(generated.get());
			}
			// TEMPLATE 비율이 30%를 넘으면 목록이 아니라 프롬프트를 손본다 (spec §후검증). 그 판단의 근거 로그다.
			log.info("{} 서술이 후검증에 걸려 템플릿으로 대체한다. 적발={}", part, validation.detectedExpressions());
		} else {
			log.debug("{} 서술 생성이 실패해 템플릿으로 대체한다.", part);
		}
		return NarrativeResultDto.template(template);
	}

	private NarrativeResultDto resolveWithRegeneration(String part, String userPrompt) {
		String systemPrompt = promptBuilder.systemPrompt();
		String currentPrompt = userPrompt;
		int maxRegeneration = properties.maxRegeneration();

		// 호출 횟수는 최대 maxRegeneration + 1회다. 무한 재시도로 비용이 늘지 않게 한다 (spec §후검증).
		for (int attempt = 0; attempt <= maxRegeneration; attempt++) {
			Optional<String> generated = generator.generate(systemPrompt, currentPrompt);
			if (generated.isEmpty()) {
				// 재생성은 후검증 적발에만 쓴다. 키 없음·타임아웃은 다시 불러도 같은 이유로 실패하므로
				// 재생성 횟수를 소비하지 않고 곧바로 NONE으로 간다 (spec §실패 처리).
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
			// 재생성 프롬프트는 직전 재생성본이 아니라 항상 원본 사용자 프롬프트에 적발 표현을 붙여 만든다.
			// 누적하면 재생성 횟수를 늘렸을 때 프롬프트가 계속 길어지고 기사 목록이 중복된다.
			currentPrompt = promptBuilder.regenerationPrompt(userPrompt, validation.detectedExpressions());
			log.info("{} 서술이 후검증에 걸려 재생성한다. 적발={}", part, validation.detectedExpressions());
		}
		return NarrativeResultDto.none();
	}
}
