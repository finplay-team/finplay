// OAuth 재인증 성공 토큰을 프론트로 직접 노출하지 않고, 1회용 교환 코드로 Redis에 잠시 보관한다.
package com.finplay.api.auth.oauth;

import com.finplay.api.auth.dto.response.ReauthTokenResponse;
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
 * {@link OAuthLoginExchangeStore}와 같은 이유로 REAUTH 콜백도 팝업 리다이렉트 URL에 실제 {@code reauthToken}을
 * 직접 싣지 않는다. {@link #issue}가 발급한 코드만 리다이렉트 URL에 싣고, 실제 토큰은 오프너가 {@link #consume}으로
 * 별도 요청해 받는다.
 *
 * <p><b>1회용이다.</b> {@code getAndDelete}로 읽음과 동시에 지워 같은 코드를 두 번 못 쓴다. TTL은 팝업이 카카오·네이버
 * 인가 화면에서 돌아와 오프너로 결과를 전달할 때까지의 정상 지연만 감당하면 되므로 짧게 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthReauthExchangeStore {

	private static final String KEY_PREFIX = "auth:oauth-reauth-exchange:v1:";

	private static final int CODE_BYTE_LENGTH = 32;

	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Duration TTL = Duration.ofSeconds(60);

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	private final SecureRandom secureRandom = new SecureRandom();

	/** 재인증 토큰을 Redis에 TTL 60초로 저장하고, 그 토큰을 나중에 한 번만 꺼낼 수 있는 교환 코드를 반환한다. */
	public String issue(ReauthTokenResponse reauthToken) {
		byte[] randomBytes = new byte[CODE_BYTE_LENGTH];
		secureRandom.nextBytes(randomBytes);
		String code = BASE64_URL_ENCODER.encodeToString(randomBytes);
		redisTemplate.opsForValue().set(KEY_PREFIX + code, objectMapper.writeValueAsString(reauthToken), TTL);
		return code;
	}

	/**
	 * 코드에 해당하는 재인증 토큰을 꺼내고 즉시 지운다. 이미 소비됐거나 TTL이 지났거나 형식이 깨진 값이면 빈 값이다 —
	 * 호출부가 400으로 거부한다.
	 */
	public Optional<ReauthTokenResponse> consume(String code) {
		String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + code);
		if (value == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(value, ReauthTokenResponse.class));
		} catch (RuntimeException ex) {
			log.warn("OAuth 재인증 교환 코드 역직렬화 실패", ex);
			return Optional.empty();
		}
	}
}
