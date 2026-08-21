// Fake callback 공급자의 지원 범위와 결정적 fixture 및 인가 실패 계약을 단위 테스트한다.
package com.finplay.api.domain.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.exchange.FakeOAuthGrantStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeOAuthCallbackProviderTest {

	private final FakeOAuthGrantStore grantStore = new FakeOAuthGrantStore();
	private final FakeOAuthCallbackProvider provider = new FakeOAuthCallbackProvider(grantStore,
		OAuthProviderName.KAKAO);

	@Test
	@DisplayName("provider별 Fake callback 공급자는 자신의 provider만 지원한다")
	void supportsOnlyConfiguredProvider() {
		assertThat(provider.supports(OAuthProviderName.KAKAO)).isTrue();
		assertThat(provider.supports(OAuthProviderName.NAVER)).isFalse();
		assertThat(provider.supports(null)).isFalse();
	}

	@Test
	@DisplayName("발급된 code와 같은 state는 결정적인 공급자 사용자 ID와 이메일을 반환한다")
	void fetchUserReturnsDeterministicDefaultUserForIssuedGrant() {
		String code = grantStore.issue(OAuthProviderName.KAKAO, "state-value");

		assertThat(provider.fetchUser(code, "state-value"))
			.isEqualTo(new OAuthUserDto("fake-oauth-user", "fake-oauth@finplay.test"));
	}

	@Test
	@DisplayName("fake-code-no-email은 이메일이 없는 결정적 사용자를 반환한다")
	void fetchUserReturnsUserWithoutEmail() {
		assertThat(provider.fetchUser("fake-code-no-email", "state-value"))
			.isEqualTo(new OAuthUserDto("fake-oauth-user", null));
	}

	@Test
	@DisplayName("fake-code-existing-email은 기존 이메일 충돌 fixture를 반환한다")
	void fetchUserReturnsExistingEmailFixture() {
		assertThat(provider.fetchUser("fake-code-existing-email", "state-value"))
			.isEqualTo(new OAuthUserDto("fake-oauth-user", "existing-oauth@finplay.test"));
	}

	@Test
	@DisplayName("알 수 없는 Fake code는 민감한 code를 노출하지 않는 인가 실패로 거부한다")
	void fetchUserRejectsUnknownCodeWithoutExposingIt() {
		String unknownCode = "unknown-sensitive-code";
		String sensitiveState = "sensitive-state";

		assertThatThrownBy(() -> provider.fetchUser(unknownCode, sensitiveState))
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.getErrorCode())
						.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
					assertThat(exception.getMessage())
						.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED.getDefaultMessage())
						.doesNotContain(unknownCode, sensitiveState);
				});
	}

	@Test
	@DisplayName("state 불일치와 재사용은 code와 state를 노출하지 않는 인가 실패다")
	void fetchUserRejectsMismatchedAndReusedGrantWithoutSensitiveValues() {
		String state = "sensitive-issued-state";
		String code = grantStore.issue(OAuthProviderName.KAKAO, state);

		assertAuthorizationFailureWithoutValues(
			() -> provider.fetchUser(code, "different-sensitive-state"),
			code,
			"different-sensitive-state");
		assertThat(provider.fetchUser(code, state))
			.isEqualTo(new OAuthUserDto("fake-oauth-user", "fake-oauth@finplay.test"));
		assertAuthorizationFailureWithoutValues(
			() -> provider.fetchUser(code, state), code, state);
	}

	private static void assertAuthorizationFailureWithoutValues(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
		String code,
		String state) {
		assertThatThrownBy(invocation)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.getErrorCode())
						.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
					assertThat(exception.getMessage())
						.isEqualTo(ErrorCode.OAUTH_AUTHORIZATION_FAILED.getDefaultMessage())
						.doesNotContain(code, state);
				});
	}
}
