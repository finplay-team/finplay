package com.finplay.api.domain.auth.oauth.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class OAuthReauthExchangeStoreTest {

	private static final ReauthTokenResponse REAUTH_TOKEN = new ReauthTokenResponse("raw-reauth-token", 300L);

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OAuthReauthExchangeStore store = new OAuthReauthExchangeStore(redisTemplate, objectMapper);

	@Test
	@DisplayName("issue는 reauthToken을 직렬화해 60초 TTL로 저장하고 시도마다 다른 코드를 준다")
	void issueStoresReauthTokenWithTtlAndReturnsAFreshCodeEachTime() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		String first = store.issue(REAUTH_TOKEN);
		String second = store.issue(REAUTH_TOKEN);

		assertThat(first).isNotEqualTo(second);
		verify(valueOperations).set(
			eq("auth:oauth-reauth-exchange:v1:" + first),
			eq(objectMapper.writeValueAsString(REAUTH_TOKEN)),
			eq(Duration.ofSeconds(60)));
	}

	@Test
	@DisplayName("consume은 저장된 코드를 reauthToken으로 되돌린다")
	void consumeReturnsTheStoredReauthToken() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.getAndDelete("auth:oauth-reauth-exchange:v1:some-code"))
			.thenReturn(objectMapper.writeValueAsString(REAUTH_TOKEN));

		assertThat(store.consume("some-code")).contains(REAUTH_TOKEN);
	}

	@Test
	@DisplayName("consume은 getAndDelete로 지우며 읽으므로 같은 코드를 두 번 못 쓴다")
	void consumeDeletesOnRead() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.getAndDelete("auth:oauth-reauth-exchange:v1:one-time"))
			.thenReturn(objectMapper.writeValueAsString(REAUTH_TOKEN), (String)null);

		assertThat(store.consume("one-time")).isPresent();
		assertThat(store.consume("one-time")).isEmpty();
	}

	@Test
	@DisplayName("존재하지 않거나 만료된 코드는 빈 값이다")
	void consumeReturnsEmptyWhenMissing() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.getAndDelete(anyString())).thenReturn(null);

		assertThat(store.consume("missing-code")).isEmpty();
	}

	@Test
	@DisplayName("형식이 깨진 값은 예외 대신 빈 값이다 — 캐시 미스와 동일하게 400으로 거부된다")
	void consumeReturnsEmptyWhenValueIsNotValidJson() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.getAndDelete(any())).thenReturn("not-json");

		assertThat(store.consume("broken-code")).isEmpty();
	}
}
