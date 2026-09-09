package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

class OpenAiNarrativeGeneratorTest {

	private static final FeedbackLlmProperties PROPERTIES = new FeedbackLlmProperties("test-model-x", 7, 321, 1, 3, 3);

	private static final String SYSTEM_PROMPT = "너는 관찰형 서술만 쓴다.";

	private static final String USER_PROMPT = "삼성전자 09:32 +2.1%";

	@Test
	@DisplayName("키가 자리표시자면 ChatClient를 한 번도 건드리지 않고 실패를 반환한다")
	void returnsFailureWithoutTouchingChatClientWhenApiKeyIsPlaceholder() {
		ChatClient chatClient = mock(ChatClient.class);
		OpenAiNarrativeGenerator generator = new OpenAiNarrativeGenerator(chatClient, PROPERTIES, new LlmCallStats(),
			OpenAiNarrativeGenerator.NOT_CONFIGURED_API_KEY);

		Optional<String> result = generator.generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(result).isEmpty();
		verifyNoInteractions(chatClient);
	}

	@ParameterizedTest(name = "apiKey=[{0}]")
	@NullSource
	@ValueSource(strings = {"", "   ", "\t\n", OpenAiNarrativeGenerator.NOT_CONFIGURED_API_KEY})
	@DisplayName("키가 없거나 공백뿐이면 ChatClient 호출 0회로 실패를 반환한다")
	void returnsFailureWithZeroChatClientCallsWhenApiKeyIsAbsent(String apiKey) {
		ChatClient chatClient = mock(ChatClient.class);
		OpenAiNarrativeGenerator generator = new OpenAiNarrativeGenerator(chatClient, PROPERTIES, new LlmCallStats(),
			apiKey);

		Optional<String> result = generator.generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(result).isEmpty();
		verifyNoInteractions(chatClient);
	}

	@Test
	@DisplayName("정상 응답은 앞뒤 공백을 제거해 그대로 반환한다")
	void returnsTrimmedNarrativeOnSuccess() {
		ChatClient chatClient = chatClientReturning("  09:32에 2.1% 올랐다.\n ");

		Optional<String> result = generatorWithKey(chatClient).generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(result).contains("09:32에 2.1% 올랐다.");
	}

	@Test
	@DisplayName("모델·최대 토큰은 feedback.llm.* 프로퍼티에서 오고 max_tokens가 아니라 max_completion_tokens로 나간다")
	void sendsModelAndMaxCompletionTokensFromProperties() {
		AtomicReference<ChatOptions.Builder<?>> captured = new AtomicReference<>();
		ChatClient chatClient = chatClient("서술", captured, null);

		generatorWithKey(chatClient).generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(captured.get()).isNotNull();
		OpenAiChatOptions options = (OpenAiChatOptions)captured.get().build();
		assertThat(options.getModel()).isEqualTo(PROPERTIES.model());
		assertThat(options.getMaxCompletionTokens()).isEqualTo(PROPERTIES.maxTokens());
		assertThat(options.getMaxTokens()).isNull();
	}

	@ParameterizedTest(name = "content=[{0}]")
	@NullSource
	@ValueSource(strings = {"", "   ", "\n\t "})
	@DisplayName("빈 응답·공백뿐인 응답은 성공으로 취급하지 않고 실패로 수렴한다")
	void returnsFailureWhenResponseIsBlank(String content) {
		ChatClient chatClient = chatClientReturning(content);

		Optional<String> result = generatorWithKey(chatClient).generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("타임아웃·HTTP 오류가 어느 호출 단계에서 터져도 예외가 위로 새어 나가지 않는다")
	void doesNotLeakExceptionFromAnyStageOfTheCallChain() {
		List<RuntimeException> failures = List.of(
			new IllegalStateException("timed out", new SocketTimeoutException("read timeout")),
			new RuntimeException("429 Too Many Requests"),
			new IllegalStateException("500 Internal Server Error", new IOException("connection reset")));

		for (RuntimeException failure : failures) {
			for (Stage stage : Stage.values()) {
				ChatClient chatClient = chatClientThrowingAt(stage, failure);
				OpenAiNarrativeGenerator generator = generatorWithKey(chatClient);

				assertThatCode(() -> assertThat(generator.generate(SYSTEM_PROMPT, USER_PROMPT)).isEmpty())
					.as("stage=%s, failure=%s", stage, failure)
					.doesNotThrowAnyException();
			}
		}
	}

	@Test
	@DisplayName("키 없음·예외·빈 응답이 전부 구별 불가능한 하나의 실패 표현으로 수렴한다")
	void everyFailureModeConvergesToTheSameValue() {
		List<Supplier<Optional<String>>> failureModes = List.of(
			() -> new OpenAiNarrativeGenerator(mock(ChatClient.class), PROPERTIES, new LlmCallStats(),
				OpenAiNarrativeGenerator.NOT_CONFIGURED_API_KEY)
				.generate(SYSTEM_PROMPT, USER_PROMPT),
			() -> generatorWithKey(chatClientThrowingAt(Stage.PROMPT, new RuntimeException("boom")))
				.generate(SYSTEM_PROMPT, USER_PROMPT),
			() -> generatorWithKey(chatClientThrowingAt(Stage.CALL, new RuntimeException("timeout")))
				.generate(SYSTEM_PROMPT, USER_PROMPT),
			() -> generatorWithKey(chatClientThrowingAt(Stage.CONTENT, new RuntimeException("HTTP 503")))
				.generate(SYSTEM_PROMPT, USER_PROMPT),
			() -> generatorWithKey(chatClientReturning("   ")).generate(SYSTEM_PROMPT, USER_PROMPT));

		Set<Optional<String>> results = new LinkedHashSet<>();
		for (Supplier<Optional<String>> failureMode : failureModes) {
			results.add(failureMode.get());
		}

		assertThat(results).containsExactly(Optional.<String>empty());
	}

	@Test
	@DisplayName("성공이든 실패든 실제로 나간 호출은 전부 횟수와 소요 시간에 기록된다")
	void recordsEveryAttemptThatActuallyLeftTheProcess() {
		LlmCallStats llmCallStats = new LlmCallStats();
		llmCallStats.startScope();

		generatorWithKey(chatClientReturning("서술"), llmCallStats).generate(SYSTEM_PROMPT, USER_PROMPT);
		generatorWithKey(chatClientThrowingAt(Stage.CALL, new RuntimeException("timeout")), llmCallStats)
			.generate(SYSTEM_PROMPT, USER_PROMPT);
		generatorWithKey(chatClientReturning("   "), llmCallStats).generate(SYSTEM_PROMPT, USER_PROMPT);

		LlmCallStats.Snapshot snapshot = llmCallStats.finishScope();
		assertThat(snapshot.count()).isEqualTo(3);
		assertThat(snapshot.totalNanos()).isPositive();
	}

	@Test
	@DisplayName("키가 없어 건너뛴 경로는 호출로 세지 않는다")
	void doesNotCountTheSkippedPathAsACall() {
		LlmCallStats llmCallStats = new LlmCallStats();
		llmCallStats.startScope();

		new OpenAiNarrativeGenerator(mock(ChatClient.class), PROPERTIES, llmCallStats,
			OpenAiNarrativeGenerator.NOT_CONFIGURED_API_KEY)
			.generate(SYSTEM_PROMPT, USER_PROMPT);

		assertThat(llmCallStats.finishScope().count()).isZero();
	}

	@Test
	@DisplayName("application.yml의 api-key 자리표시자 기본값이 코드 상수와 같다 — 키 없음 판정의 정의는 한 곳뿐이다")
	void applicationYmlPlaceholderMatchesTheSingleNotConfiguredConstant() throws IOException {
		String applicationYml = StreamUtils.copyToString(
			new ClassPathResource("application.yml").getInputStream(), StandardCharsets.UTF_8);

		Matcher matcher = Pattern.compile("api-key:\\s*\\$\\{OPENAI_API_KEY:([^}]*)}").matcher(applicationYml);

		assertThat(matcher.find())
			.as("application.yml에 spring.ai.openai.api-key 자리표시자가 있어야 한다")
			.isTrue();
		assertThat(matcher.group(1)).isEqualTo(OpenAiNarrativeGenerator.NOT_CONFIGURED_API_KEY);
	}

	private OpenAiNarrativeGenerator generatorWithKey(ChatClient chatClient) {
		return generatorWithKey(chatClient, new LlmCallStats());
	}

	private OpenAiNarrativeGenerator generatorWithKey(ChatClient chatClient, LlmCallStats llmCallStats) {
		return new OpenAiNarrativeGenerator(chatClient, PROPERTIES, llmCallStats, "sk-test-not-a-real-key");
	}

	private ChatClient chatClientReturning(String content) {
		return chatClient(content, new AtomicReference<>(), null);
	}

	private ChatClient chatClientThrowingAt(Stage stage, RuntimeException failure) {
		return chatClient(null, new AtomicReference<>(), new Failure(stage, failure));
	}

	private ChatClient chatClient(String content, AtomicReference<ChatOptions.Builder<?>> captured, Failure failure) {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

		if (failure != null && failure.stage() == Stage.PROMPT) {
			given(chatClient.prompt()).willThrow(failure.exception());
			return chatClient;
		}
		given(chatClient.prompt()).willReturn(requestSpec);
		given(requestSpec.system(anyString())).willReturn(requestSpec);
		given(requestSpec.user(anyString())).willReturn(requestSpec);
		given(requestSpec.options(any())).willAnswer(invocation -> {
			captured.set(invocation.getArgument(0));
			return requestSpec;
		});

		if (failure != null && failure.stage() == Stage.CALL) {
			given(requestSpec.call()).willThrow(failure.exception());
			return chatClient;
		}
		given(requestSpec.call()).willReturn(responseSpec);

		if (failure != null && failure.stage() == Stage.CONTENT) {
			given(responseSpec.content()).willThrow(failure.exception());
			return chatClient;
		}
		given(responseSpec.content()).willReturn(content);
		return chatClient;
	}

	private enum Stage {
		PROMPT, CALL, CONTENT
	}

	private record Failure(Stage stage, RuntimeException exception) {
	}
}
