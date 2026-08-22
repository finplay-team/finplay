// 파트별 서술을 확정하는 이 도메인의 유일한 진입점 — 생성 → 후검증 → 폴백(템플릿 또는 재생성)을 실행한다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p><b>후검증 축이 둘이다</b> (spec 053). 표현 대조({@link NarrativeValidator})는 네 파트 전부에 걸리고,
 * 숫자 대조({@link NarrativeNumberValidator})는 <b>매도 회고에만</b> 걸린다. 두 축을 합치는 자리는 이
 * 클래스 한 곳뿐이며, 적발 목록을 이어 붙여 <b>기존 폴백 분기 하나</b>에 그대로 넘긴다 — 위반 시 동작이
 * 축마다 갈리면 조용히 드리프트한다 (FEED-017).
 */
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

	/**
	 * 변동 원인 카드 (1단계). 실패·적발 시 §템플릿 문장의 장중 카드 또는 시가 갭 문장으로 대체된다.
	 *
	 * <p>숫자 대조를 걸지 않는다({@code numberSourcePrompt}가 {@code null}). 판단이 아니라 필수다 — 카드
	 * 프롬프트는 구간을 {@code 09:32 ~ 09:37}로만 주고 <b>구간 길이(분)를 주지 않는데</b> 카드 템플릿 문장은
	 * {@code 5분간}이라고 쓴다. 카드에 축을 걸면 <b>폴백 문장 자체가 위반</b>이 되어 대체 경로가 성립하지
	 * 않는다 (053 plan §결정 B).
	 */
	public NarrativeResultDto resolvePriceMoveNarrative(PriceMovePromptDto input) {
		return resolveWithTemplateFallback(
			"변동 카드", promptBuilder.priceMovePrompt(input), templateBuilder.priceMoveTemplate(input), null);
	}

	/**
	 * 매도 직후 회고 (1단계). {@code narrativeStatus}가 항상 {@code READY}인 근거가 이 폴백이다.
	 *
	 * <p>숫자 대조의 허용 집합은 <b>자기 사용자 프롬프트 문자열 그대로</b>다 (053 spec §결정 2). DTO가 넘긴
	 * 값만 따로 모으면 프롬프트와 허용 집합을 두 곳에서 동기화해야 하는데, 프롬프트 하나에서 만들면 프롬프트를
	 * 고치는 것만으로 허용 집합이 따라온다.
	 */
	public NarrativeResultDto resolvePostSellNarrative(PostSellPromptDto input) {
		String userPrompt = promptBuilder.postSellPrompt(input);
		return resolveWithTemplateFallback(
			"매도 회고", userPrompt, templateBuilder.postSellTemplate(input), userPrompt);
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
	//
	// numberSourcePrompt가 null이면 숫자 대조를 건너뛴다. 파트별로 메서드를 복제하지 않는 이유는 FEED-017이다 —
	// 생성 실패·적발·폴백·로그가 두 파트에서 같은 코드여야 다음 사람이 한쪽만 고쳐 갈리는 일이 없다.
	private NarrativeResultDto resolveWithTemplateFallback(
		String part, String userPrompt, String template, String numberSourcePrompt) {
		Optional<String> generated = generator.generate(promptBuilder.systemPrompt(), userPrompt);
		if (generated.isPresent()) {
			List<String> detected = detect(generated.get(), numberSourcePrompt);
			if (detected.isEmpty()) {
				return NarrativeResultDto.llm(generated.get());
			}
			// TEMPLATE 비율이 30%를 넘으면 목록이 아니라 프롬프트를 손본다 (spec §후검증). 그 판단의 근거 로그다.
			log.info("{} 서술이 후검증에 걸려 템플릿으로 대체한다. 적발={}", part, detected);
		} else {
			log.debug("{} 서술 생성이 실패해 템플릿으로 대체한다.", part);
		}
		return NarrativeResultDto.template(template);
	}

	// 두 축의 적발 목록을 이어 붙인다. 표현이 앞, 숫자가 뒤인 순서를 고정해 로그가 재현 가능하게 둔다.
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
