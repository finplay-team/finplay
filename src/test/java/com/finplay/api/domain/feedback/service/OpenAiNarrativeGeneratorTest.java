// OpenAiNarrativeGenerator가 모든 실패를 예외 없이 Optional.empty()로 수렴시키는지 검증하는 단위 테스트.
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

// ADR-0011: 자동 테스트는 실제 OpenAI를 호출하지 않는다. ChatClient는 전부 mock이고, 이 파일은 환경변수
// OPENAI_API_KEY를 읽는 경로를 만들지 않는다 — 키 문자열은 생성자 인자로 직접 준다.
//
// 이 클래스가 지키는 계약은 하나다. "LLM 쪽에서 무슨 일이 나도 호출부는 Optional.empty() 하나만 본다."
// 여기가 뚫리면 LLM 장애가 조회 API를 500으로 만든다.
class OpenAiNarrativeGeneratorTest {

	// 프로퍼티에서 온 값이 요청에 실리는지 보려면 §C-7 기본값과 달라야 한다 — 같으면 코드에 박아도 통과한다.
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

	// 자리표시자 리터럴을 여기 다시 적지 않는다 — NOT_CONFIGURED_API_KEY는 컴파일 상수라 애너테이션에 그대로 쓸 수 있다.
	// 리터럴을 복사하면 "키 없음" 판정이 두 곳이 되어 한쪽만 고치는 사고가 난다.
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
		// GPT-5 계열은 요청 본문의 max_tokens를 거부한다 (FeedbackLlmProperties.maxTokens 주석). 여기에 값이
		// 실리면 전 호출이 실패해 서술이 통째로 템플릿이 되므로 비어 있어야 한다.
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

		// 호출부(NarrativeService)는 이 하나만 보고 템플릿으로 폴백한다 — 실패 종류를 구분할 수 있으면 안 된다.
		assertThat(results).containsExactly(Optional.<String>empty());
	}

	// 이슈 #198 — 배치가 "이번에 LLM을 몇 번 불렀는지"를 아는 유일한 근거가 이 기록이다.
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
		// 타임아웃은 20초를 통째로 쓰고도 실패한다 — 실패를 빼고 세면 마감을 넘긴 날의 원인이 통계에서 사라진다.
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
		// 이 둘이 갈리면 키를 안 넣은 환경에서 generator가 "키가 있다"고 믿고 실제 호출을 시도한다.
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

	// ChatClient의 유창한 체인(prompt → system → user → options → call → content)을 단계별 mock으로 세운다.
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

	// 예외가 터지는 지점. 한 지점만 막아 두고 나머지를 놓치는 일이 없도록 전 지점을 순회한다.
	private enum Stage {
		PROMPT, CALL, CONTENT
	}

	private record Failure(Stage stage, RuntimeException exception) {
	}
}
