// Redis Testcontainer 연결 후 값 읽기/쓰기가 되는지 확인하는 통합 스모크 테스트 (ADR-0003)
package com.finplay.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisConnectionTest {

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	void redisReadWrite() {
		redisTemplate.opsForValue().set("smoke:key", "pong");
		assertEquals("pong", redisTemplate.opsForValue().get("smoke:key"));
	}
}
