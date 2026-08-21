// Fake OAuth code의 state 결합·단일 소비·동시 원자성과 특수 fixture를 통합 검증한다.
package com.finplay.api.domain.auth.oauth.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.provider.FakeOAuthAuthorizationProvider;
import com.finplay.api.domain.auth.oauth.provider.FakeOAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.provider.OAuthCallbackProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FakeOAuthCodeConsumptionIntegrationTest {

	private static final OAuthUserDto DEFAULT_USER = new OAuthUserDto("fake-oauth-user", "fake-oauth@finplay.test");

	@Autowired
	private FakeOAuthAuthorizationProvider authorizationProvider;

	@Autowired
	private List<OAuthCallbackProvider> callbackProviders;

	@Autowired
	private FakeOAuthGrantStore grantStore;

	@Test
	void springAuthorizationAndCallbackProvidersShareSameGrantStoreBean() {
		assertThat(ReflectionTestUtils.getField(authorizationProvider, "grantStore"))
			.isSameAs(grantStore);
		assertThat(callbackProviders)
			.filteredOn(FakeOAuthCallbackProvider.class::isInstance)
			.hasSize(2)
			.allSatisfy(provider -> assertThat(
				ReflectionTestUtils.getField(provider, "grantStore"))
				.isSameAs(grantStore));
	}

	@Test
	void differentStatesReceiveDifferentCodesBoundToTheirOwnState() {
		Authorization first = authorize("state-first");
		Authorization second = authorize("state-second");

		assertThat(first.code()).isNotBlank().isNotEqualTo(second.code());
		assertThat(callbackProvider(OAuthProviderName.KAKAO)
			.fetchUser(first.code(), first.state()))
			.isEqualTo(DEFAULT_USER);
		assertThatThrownBy(() -> callbackProvider(OAuthProviderName.KAKAO)
			.fetchUser(second.code(), first.state()))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED));
	}

	@Test
	void generatedCodeSucceedsOnlyOnceForMatchingState() {
		Authorization authorization = authorize("single-use-state");

		assertThat(callbackProvider(OAuthProviderName.KAKAO).fetchUser(
			authorization.code(), authorization.state())).isEqualTo(DEFAULT_USER);
		assertThatThrownBy(() -> callbackProvider(OAuthProviderName.KAKAO).fetchUser(
			authorization.code(), authorization.state()))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED));
	}

	@Test
	void concurrentReuseAllowsExactlyOneSuccess() throws Exception {
		Authorization authorization = authorize("concurrent-state");
		int attempts = 8;
		CountDownLatch ready = new CountDownLatch(attempts);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(attempts);
		Callable<Attempt> callback = () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("concurrent callback start timeout");
			}
			try {
				callbackProvider(OAuthProviderName.KAKAO)
					.fetchUser(authorization.code(), authorization.state());
				return Attempt.succeeded();
			} catch (BusinessException ex) {
				return Attempt.failed(ex.getErrorCode());
			}
		};

		try {
			var futures = IntStream.range(0, attempts)
				.mapToObj(index -> executor.submit(callback))
				.toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var results = futures.stream()
				.map(FakeOAuthCodeConsumptionIntegrationTest::get)
				.toList();

			assertThat(results).filteredOn(Attempt::success).hasSize(1);
			assertThat(results)
				.filteredOn(attempt -> !attempt.success())
				.extracting(Attempt::errorCode)
				.containsOnly(ErrorCode.OAUTH_AUTHORIZATION_FAILED)
				.hasSize(attempts - 1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void specialFixtureCodesRemainAvailable() {
		assertThat(callbackProvider(OAuthProviderName.KAKAO)
			.fetchUser("fake-code-no-email", "fixture-state"))
			.isEqualTo(new OAuthUserDto("fake-oauth-user", null));
		assertThat(callbackProvider(OAuthProviderName.NAVER)
			.fetchUser("fake-code-existing-email", "fixture-state"))
			.isEqualTo(new OAuthUserDto(
				"fake-oauth-user", "existing-oauth@finplay.test"));
	}

	@Test
	void wrongProviderDoesNotConsumeGrantBeforeOriginalProviderUsesItOnce() {
		Authorization authorization = authorize(
			OAuthProviderName.KAKAO, "provider-bound-state");

		assertThatThrownBy(() -> callbackProvider(OAuthProviderName.NAVER)
			.fetchUser(authorization.code(), authorization.state()))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED));
		assertThat(callbackProvider(OAuthProviderName.KAKAO)
			.fetchUser(authorization.code(), authorization.state()))
			.isEqualTo(DEFAULT_USER);
		assertThatThrownBy(() -> callbackProvider(OAuthProviderName.KAKAO)
			.fetchUser(authorization.code(), authorization.state()))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED));
	}

	private Authorization authorize(String state) {
		return authorize(OAuthProviderName.KAKAO, state);
	}

	private Authorization authorize(OAuthProviderName provider, String state) {
		URI uri = authorizationProvider.createAuthorizationUri(
			provider, state);
		Map<String, String> query = queryParameters(uri);
		return new Authorization(query.get("code"), query.get("state"));
	}

	private OAuthCallbackProvider callbackProvider(OAuthProviderName provider) {
		return callbackProviders.stream()
			.filter(candidate -> candidate instanceof FakeOAuthCallbackProvider)
			.filter(candidate -> candidate.supports(provider))
			.findFirst()
			.orElseThrow();
	}

	private static Attempt get(Future<Attempt> future) {
		try {
			return future.get(20, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Map<String, String> queryParameters(URI uri) {
		return Arrays.stream(uri.getRawQuery().split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(Collectors.toMap(
				parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
				parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
	}

	private record Authorization(String code, String state) {
	}

	private record Attempt(boolean success, ErrorCode errorCode) {

		private static Attempt succeeded() {
			return new Attempt(true, null);
		}

		private static Attempt failed(ErrorCode errorCode) {
			return new Attempt(false, errorCode);
		}
	}
}
