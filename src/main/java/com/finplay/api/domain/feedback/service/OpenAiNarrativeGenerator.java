package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
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

	static final String NOT_CONFIGURED_API_KEY = "not-configured";

	private final ChatClient chatClient;
	private final FeedbackLlmProperties properties;
	private final LlmCallStats llmCallStats;
	private final boolean apiKeyConfigured;

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
			log.debug("OpenAI API 키가 없어 LLM 호출을 건너뛴다. 서술은 템플릿으로 대체된다.");
			return Optional.empty();
		}
		long startedNanos = System.nanoTime();
		try {
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
			log.warn("LLM 호출이 실패했다. model={}", properties.model(), e);
			return Optional.empty();
		} finally {
			long elapsedNanos = System.nanoTime() - startedNanos;
			llmCallStats.record(elapsedNanos);
			log.info("LLM 호출을 마쳤다. model={} 소요={}ms", properties.model(), elapsedNanos / 1_000_000L);
		}
	}
}
