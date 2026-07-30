// 활성 BithumbFeedClient 빈의 생명주기를 애플리케이션 기동·종료에 맞추는 컴포넌트
package com.finplay.api.market.feed;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BithumbFeedLifecycle {

	private final BithumbFeedClient bithumbFeedClient;

	@EventListener(ApplicationReadyEvent.class)
	public void startFeed() {
		log.info("빗썸 시세 피드 시작");
		bithumbFeedClient.start();
	}

	@PreDestroy
	public void stopFeed() {
		log.info("빗썸 시세 피드 종료");
		bithumbFeedClient.stop();
	}
}
