// Redis Testcontainer 연결 후 값 읽기/쓰기가 되는지 확인하는 통합 스모크 테스트 (ADR-0003)
package com.finplay.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisConnectionIntegrationTest {

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	@Test
	void redisStoresAndReturnsValue() {
		redisTemplate.opsForValue().set("smoke:key", "pong");
		assertThat(redisTemplate.opsForValue().get("smoke:key")).isEqualTo("pong");
	}

	// application.yml의 spring.data.redis.timeout이 실제 커넥션 팩토리까지 도달했는지 본다.
	// RedisCommandTimeoutConfigTest는 yml에 값이 적혀 있는지만 보므로, 키 경로가 틀리면 그쪽은 통과하고
	// 여기만 깨진다 — 둘이 짝이다. 값이 안 붙으면 Lettuce 기본 60초가 되어 블랙홀 단절에서 조회 하나가
	// 톰캣 스레드를 60초 문다(그 이유는 RedisCommandTimeoutConfigTest 주석에 있다).
	@Test
	void commandTimeoutFromApplicationYmlReachesTheConnectionFactory() {
		assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);

		long timeoutMillis = ((LettuceConnectionFactory)redisConnectionFactory).getTimeout();

		assertThat(timeoutMillis)
			.as("Lettuce 기본 60초로 돌아가면 안 된다")
			.isPositive()
			.isLessThanOrEqualTo(Duration.ofSeconds(5).toMillis());
	}
}
