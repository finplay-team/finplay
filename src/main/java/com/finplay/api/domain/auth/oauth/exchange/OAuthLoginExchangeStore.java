package com.finplay.api.domain.auth.oauth.exchange;

import com.finplay.api.domain.auth.dto.response.TokenResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthLoginExchangeStore {

	private static final String KEY_PREFIX = "auth:oauth-login-exchange:v1:";

	private static final int CODE_BYTE_LENGTH = 32;

	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Duration TTL = Duration.ofSeconds(60);

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	private final SecureRandom secureRandom = new SecureRandom();

	public String issue(TokenResponse tokens) {
		byte[] randomBytes = new byte[CODE_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		String code = BASE64_URL_ENCODER.encodeToString(randomBytes);
		redisTemplate.opsForValue().set(KEY_PREFIX + code, objectMapper.writeValueAsString(tokens), TTL);
		return code;
	}

	public Optional<TokenResponse> consume(String code) {
		String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code);
		if (value == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(value, TokenResponse.class));
		} catch (RuntimeException ex) {
			log.warn("OAuth 로그인 교환 코드 역직렬화 실패", ex);
			return Optional.empty();
		}
	}
}
