// 서술 생성용 ChatClient 빈을 조립하는 설정 클래스 — Spring AI 자동설정에 의존하는 부분을 여기에 모은다.
package com.finplay.api.domain.feedback.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// FeedbackLlmConfig(프로퍼티 등록)와 분리한 이유는 생명주기가 다르기 때문이다 — 프로퍼티 바인딩은 외부 의존이
// 없어 순수 슬라이스로 검증하고, ChatClient는 Spring AI 자동설정이 올라온 컨텍스트에서만 만들어진다.
// @Bean 메서드가 하나뿐이라 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
@Configuration(proxyBeanMethods = false)
public class NarrativeChatClientConfig {

	// 완성된 ChatClient를 그대로 주입받게 한다 — 생성자에서 빌더를 조립하면 SpotBugs EI_EXPOSE_REP2에 걸리기 쉽다
	// (KisRestClientConfig가 RestClient에 같은 이유로 쓰는 방식, ai/agent-mistakes.md 2026-07-29).
	//
	// Spring AI 2.0은 ChatModel 빈이 하나일 때 ChatClient.Builder만 자동 등록하고 ChatClient 자체는 만들지 않는다.
	// 호출 타임아웃을 여기서 주지 않는 이유는 그것이 요청 단위가 아니라 클라이언트 단위 값이기 때문이다 —
	// 근거는 application.yml의 spring.ai.openai.timeout 주석에 있다.
	@Bean
	public ChatClient narrativeChatClient(ChatClient.Builder builder) {
		return builder.build();
	}
}
