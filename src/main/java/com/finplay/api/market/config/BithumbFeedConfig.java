// 빗썸 WebSocket 클라이언트가 쓰는 StandardWebSocketClient·재연결 스케줄러를 빈으로 등록한다.
package com.finplay.api.market.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

// BithumbWebSocketFeedClient가 이 두 협력 객체를 필드 초기화자 대신 생성자로 주입받게 해, @RequiredArgsConstructor를
// 유지하면서도(SpotBugs EI_EXPOSE_REP2 회피 패턴, docs/agent-mistakes.md 2026-07-29) 재연결 경로(끊김→DISCONNECTED→
// 재연결 예약, MKT-004)를 목(mock) ScheduledExecutorService로 단위 테스트할 수 있게 한다 (PR #110 리뷰 권장사항).
// @Bean 메서드가 둘이지만 서로를 호출하지 않아 self-invocation이 없으므로 CGLIB 프록시(proxyBeanMethods)가 필요 없다.
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
