package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class RedisCommandTimeoutConfigTest {

	private static final String COMMAND_TIMEOUT_KEY = "spring.data.redis.timeout";

	private static final String CONNECT_TIMEOUT_KEY = "spring.data.redis.connect-timeout";

	private static final Duration SANE_UPPER_BOUND = Duration.ofSeconds(5);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	@DisplayName("application.yml이 Redis 명령·접속 타임아웃을 선언하고 둘 다 Lettuce 기본 60초보다 훨씬 짧다")
	void applicationYmlDeclaresShortRedisTimeouts() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

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

	private static Duration parseTimeout(String value) {
		return value == null ? null : DurationStyle.detectAndParse(value);
	}
}
