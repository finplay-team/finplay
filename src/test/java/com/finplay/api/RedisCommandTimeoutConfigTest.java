// spring.data.redis의 명령·접속 타임아웃이 application.yml에 실제로 있고 Lettuce 기본 60초가 아닌지 고정한다.
package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * <b>이 두 값이 없으면 Lettuce 기본이 60초다.</b> Redis가 죽어 접속이 즉시 거절되는 상황은
 * {@code FeedbackQueryCacheBoundaryIntegrationTest}가 이미 보고 있지만, <b>블랙홀 단절</b>(패킷이 그냥
 * 사라지는 상태 — 보안그룹 변경·NAT 만료)에서는 거절이 오지 않아 명령이 타임아웃까지 매달린다. 그러면 조회
 * 요청 하나가 톰캣 스레드를 60초 물고, 조회 트래픽이 스레드 풀을 채워 <b>주문·시세까지 함께 멎는다</b> —
 * 캐시가 없어 느려지는 것과는 차원이 다른 실패다.
 *
 * <p><b>왜 블랙홀을 재현하지 않는가.</b> 재현하려면 패킷을 삼키는 소켓을 띄우고 최소 타임아웃만큼 실제로
 * 기다려야 해서 느린 테스트가 된다. 여기서 막으려는 것은 "<b>설정이 사라지거나 비어도 아무 테스트가 안
 * 깨지는 상태</b>"이므로, 값이 선언돼 있고 상식적인 상한 안에 있는지만 고정한다.
 *
 * <p>{@code ConfigDataApplicationContextInitializer}가 {@code SpringApplication} 부트스트랩과 같은 방식으로
 * {@code application.yml}만 {@code Environment}에 얹어 준다 — Docker 없이 돈다
 * ({@code FeedbackCryptoPropertiesYamlTest}와 같은 방식).
 */
class RedisCommandTimeoutConfigTest {

	private static final String COMMAND_TIMEOUT_KEY = "spring.data.redis.timeout";

	private static final String CONNECT_TIMEOUT_KEY = "spring.data.redis.connect-timeout";

	// 이 상한을 넘으면 "설정했지만 사실상 없는 것과 같은" 값이다. 정확히 2초를 못박지 않는 것은 운영에서
	// 실측으로 조정할 값이기 때문이고, 60초(Lettuce 기본)로 되돌아가는 것만 막으면 된다.
	private static final Duration SANE_UPPER_BOUND = Duration.ofSeconds(5);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	@DisplayName("application.yml이 Redis 명령·접속 타임아웃을 선언하고 둘 다 Lettuce 기본 60초보다 훨씬 짧다")
	void applicationYmlDeclaresShortRedisTimeouts() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			// 값을 문자열로 받아 직접 파싱한다 — 이 러너의 Environment에는 Boot의 변환 서비스가 붙어 있지
			// 않아 Duration.class로 바로 요청하면 ConverterNotFoundException이 난다.
			Duration commandTimeout = parseTimeout(environment.getProperty(COMMAND_TIMEOUT_KEY));
			Duration connectTimeout = parseTimeout(environment.getProperty(CONNECT_TIMEOUT_KEY));

			assertThat(commandTimeout)
				.as("%s가 없으면 블랙홀 단절에서 조회가 요청당 60초 톰캣 스레드를 문다", COMMAND_TIMEOUT_KEY)
				.isNotNull()
				.isPositive()
				.isLessThanOrEqualTo(SANE_UPPER_BOUND);
			assertThat(connectTimeout)
				.as("%s가 없으면 접속 단계에서 같은 일이 난다", CONNECT_TIMEOUT_KEY)
				.isNotNull()
				.isPositive()
				.isLessThanOrEqualTo(SANE_UPPER_BOUND);
		});
	}

	// yml이 쓰는 "2s" 같은 표기를 Boot와 같은 규칙으로 읽는다. 값이 아예 없으면 null을 그대로 흘려보내
	// 위 isNotNull 단정이 원인을 드러내게 한다.
	private static Duration parseTimeout(String value) {
		return value == null ? null : DurationStyle.detectAndParse(value);
	}
}
