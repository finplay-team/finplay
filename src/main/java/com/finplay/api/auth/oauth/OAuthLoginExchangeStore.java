// OAuth 로그인 성공 토큰을 프론트로 직접 노출하지 않고, 1회용 교환 코드로 Redis에 잠시 보관한다.
package com.finplay.api.auth.oauth;

import com.finplay.api.auth.dto.response.TokenResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * OAuth callback이 로그인 토큰을 URL(쿼리·프래그먼트)에 직접 실어 보내면 서버 접근 로그·Referer 헤더·브라우저
 * 히스토리에 JWT가 남는다. 그 대신 {@link #issue}가 발급한 코드만 리다이렉트 URL에 싣고, 실제 토큰은 프론트가
 * {@link #consume}으로 별도 요청해 받는다.
 *
 * <p><b>1회용이다.</b> {@code getAndDelete}로 읽음과 동시에 지워 같은 코드를 두 번 못 쓴다. TTL은 브라우저가
 * 카카오·네이버 인가 화면에서 돌아와 프론트 라우트가 뜰 때까지의 정상 지연만 감당하면 되므로 짧게 둔다.
 */
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

	/** 토큰을 Redis에 TTL 60초로 저장하고, 그 토큰을 나중에 한 번만 꺼낼 수 있는 교환 코드를 반환한다. */
	public String issue(TokenResponse tokens) {
		byte[] randomBytes = new byte[CODE_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		String code = BASE64_URL_ENCODER.encodeToString(randomBytes);
		redisTemplate.opsForValue().set(KEY_PREFIX + code, objectMapper.writeValueAsString(tokens), TTL);
		return code;
	}

	/**
	 * 코드에 해당하는 토큰을 꺼내고 즉시 지운다. 이미 소비됐거나 TTL이 지났거나 형식이 깨진 값이면 빈 값이다 —
	 * 호출부가 400으로 거부한다.
	 */
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
