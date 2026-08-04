// Spring AI ChatClient로 OpenAI chat completion을 호출하는 NarrativeGenerator 구현.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackLlmProperties;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class OpenAiNarrativeGenerator implements NarrativeGenerator {

	// application.yml의 spring.ai.openai.api-key 자리표시자 기본값(spec §C-7·§외부 API 호출 상세). 이 값이 곧 "키 없음"이며,
	// 그 판정을 하는 자리는 이 클래스 한 곳뿐이다 — 여러 곳에 흩어지면 한 곳만 고치는 사고가 난다. 테스트도 리터럴을
	// 다시 적지 말고 이 상수를 참조한다.
	static final String NOT_CONFIGURED_API_KEY = "not-configured";

	private final ChatClient chatClient;
	private final FeedbackLlmProperties properties;
	private final LlmCallStats llmCallStats;
	private final boolean apiKeyConfigured;

	// @Value 주입 필드가 있으므로 @RequiredArgsConstructor를 쓰지 않고 생성자를 손으로 쓴다 — Lombok은 필드의 @Value를
	// 생성자 파라미터로 옮기지 않아 컨텍스트가 기동하지 않는다 (docs/agent-mistakes.md 2026-07-30).
	public OpenAiNarrativeGenerator(ChatClient narrativeChatClient, FeedbackLlmProperties properties,
		LlmCallStats llmCallStats,
		@Value("${spring.ai.openai.api-key:}")
		String apiKey) {
		this.chatClient = narrativeChatClient;
		this.properties = properties;
		this.llmCallStats = llmCallStats;
		this.apiKeyConfigured = StringUtils.hasText(apiKey) && !NOT_CONFIGURED_API_KEY.equals(apiKey);
	}

	@Override
	public Optional<String> generate(String systemPrompt, String userPrompt) {
		if (!apiKeyConfigured) {
			// 키가 없으면 호출 자체를 하지 않는다 (spec §실패 처리). 배치가 종목 수만큼 도는 경로라 WARN이면 로그가 넘친다.
			log.debug("OpenAI API 키가 없어 LLM 호출을 건너뛴다. 서술은 템플릿으로 대체된다.");
			return Optional.empty();
		}
		// 실제로 원격 호출이 나가는 구간만 잰다 — 키가 없어 건너뛴 위 경로는 호출이 아니므로 세지 않는다.
		// 성공·빈 응답·예외를 가리지 않고 finally에서 한 번 기록한다. 타임아웃도 시간을 쓴 호출이고, 개장 전
		// 배치가 마감을 넘기는지 보려면 실패한 호출의 시간이야말로 빠지면 안 된다 (이슈 #198).
		long startedNanos = System.nanoTime();
		try {
			// 모델·최대 토큰은 요청마다 feedback.llm.*에서 받는다. 타임아웃만 클라이언트 단위 값이라 이 경로로 넘길 수
			// 없고 application.yml의 spring.ai.openai.timeout이 맡는다. maxTokens가 아니라 maxCompletionTokens를
			// 쓰는 이유는 FeedbackLlmProperties의 해당 필드 주석에 있다.
			String narrative = chatClient.prompt()
				.system(systemPrompt)
				.user(userPrompt)
				.options(OpenAiChatOptions.builder()
					.model(properties.model())
					.maxCompletionTokens(properties.maxTokens()))
				.call()
				.content();
			if (!StringUtils.hasText(narrative)) {
				log.warn("LLM이 빈 응답을 반환했다. model={}", properties.model());
				return Optional.empty();
			}
			return Optional.of(narrative.strip());
		} catch (RuntimeException e) {
			// 타임아웃·HTTP 오류가 전부 여기로 수렴한다 (OpenAI SDK의 OpenAIException은 RuntimeException이다).
			// 예외를 위로 던지면 배치 한 건의 실패가 배치 전체를 죽인다 (ADR-0011).
			log.warn("LLM 호출이 실패했다. model={}", properties.model(), e);
			return Optional.empty();
		} finally {
			long elapsedNanos = System.nanoTime() - startedNanos;
			llmCallStats.record(elapsedNanos);
			log.info("LLM 호출을 마쳤다. model={} 소요={}ms", properties.model(), elapsedNanos / 1_000_000L);
		}
	}
}
