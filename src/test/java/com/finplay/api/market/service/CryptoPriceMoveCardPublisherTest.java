// CryptoPriceMoveCardPublisher의 정상 발행 payload·채널과 Redis 예외 흡수(호출부 미전파)를 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CryptoPriceMoveCardPublisherTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 15, 30, 0);

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	// Boot가 주는 것과 같은 종류(Jackson 3)의 진짜 매퍼를 쓴다 — 직렬화 결과를 mock으로 흉내 내면 payload 형태를
	// 이 테스트가 전혀 보지 못한다 (FeedbackQueryCacheTest와 동일 관례).
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

	private final CryptoPriceMoveCardPublisher publisher = new CryptoPriceMoveCardPublisher(
		redisTemplate, objectMapper, clock);

	@Test
	void publishSendsSerializedEventToTheChannelConstant() {
		publisher.publish(5L, 100L);

		ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(redisTemplate).convertAndSend(eq(CryptoPriceMoveCardPublisher.CHANNEL), payloadCaptor.capture());
		assertThat(CryptoPriceMoveCardPublisher.CHANNEL).isEqualTo("feedback:price-move:crypto-confirmed");

		JsonNode json = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(json.get("market").asString()).isEqualTo("CRYPTO");
		assertThat(json.get("instrumentId").asLong()).isEqualTo(5L);
		assertThat(json.get("priceMoveEventId").asLong()).isEqualTo(100L);
		assertThat(json.get("emittedAt").asString()).isEqualTo("2026-08-10T15:30:00");
	}

	@Test
	void publishSwallowsRedisExceptionWithoutPropagatingToCaller() {
		doThrow(new RuntimeException("redis down")).when(redisTemplate).convertAndSend(anyString(), any());

		assertThatCode(() -> publisher.publish(5L, 100L)).doesNotThrowAnyException();
	}
}
