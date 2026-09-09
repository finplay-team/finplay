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
