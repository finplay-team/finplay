package com.finplay.api.domain.feedback.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NarrativeChatClientConfig {

	@Bean
	public ChatClient narrativeChatClient(ChatClient.Builder builder) {
		return builder.build();
	}
}
