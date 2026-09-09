package com.finplay.api.domain.market.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class BithumbFeedConfig {

	@Bean
	public StandardWebSocketClient bithumbWebSocketClient() {
		return new StandardWebSocketClient();
	}

	@Bean
	public ScheduledExecutorService bithumbReconnectExecutor() {
		return Executors.newSingleThreadScheduledExecutor();
	}
}
