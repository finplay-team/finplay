// 코인 변동 카드 확정 이벤트를 Redis pub/sub 채널로 발행하는 단일 창구 — 채널 이름은 이 클래스에서만 조립 (ADR-0018 §결정 2·6·7)
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.PriceMoveCardConfirmedEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * feedback(`CryptoPriceMoveWatcher`)은 이 서비스만 의존한다 — feedback이 Redis를 직접 만지지 않는다(ADR-0002를
 * feedback→market 방향에 적용, ADR-0018 §결정 2). 발행 실패는 카드 생성 자체를 실패시키지 않아야 하므로
 * {@code RuntimeException}은 이 메서드 안에서 삼키고 WARN 로그만 남긴다(ADR-0018 §결정 3) — 호출부는 이 호출의
 * 성공 여부를 확인하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPriceMoveCardPublisher {

	// 기존 Redis 키 접두사(price:crypto:·ranking:·feedback:crypto-watch:lock:·feedback:query-cache:·candle:crypto:)
	// 어느 것과도 겹치지 않는다(ADR-0018 §결정 7). 이 상수 하나로만 채널 이름을 관리하고, 구독 측(RedisPubSubConfig)이
	// 이 값을 참조한다 — 문자열을 두 곳에 중복하지 않는다.
	public static final String CHANNEL = "feedback:price-move:crypto-confirmed";

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	private final Clock clock;

	public void publish(Long instrumentId, Long priceMoveEventId) {
		try {
			PriceMoveCardConfirmedEvent event = new PriceMoveCardConfirmedEvent(Market.CRYPTO, instrumentId,
				priceMoveEventId, LocalDateTime.now(clock));
			String payload = objectMapper.writeValueAsString(event);
			redisTemplate.convertAndSend(CHANNEL, payload);
		} catch (RuntimeException ex) {
			log.warn("코인 변동 카드 확정 Redis 발행 실패 - instrumentId={}, priceMoveEventId={}", instrumentId,
				priceMoveEventId, ex);
		}
	}
}
