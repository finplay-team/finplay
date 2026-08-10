// CryptoCardPushSubscriber를 CryptoPriceMoveCardPublisher.CHANNEL에 구독시키는 RedisMessageListenerContainer 빈 등록 (ADR-0018 §결정 4)
package com.finplay.api.market.config;

import com.finplay.api.market.service.CryptoPriceMoveCardPublisher;
import com.finplay.api.market.sse.CryptoCardPushSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

	private final CryptoCardPushSubscriber cryptoCardPushSubscriber;

	@Bean
	public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory redisConnectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(cryptoCardPushSubscriber, new ChannelTopic(CryptoPriceMoveCardPublisher.CHANNEL));
		return container;
	}
}
