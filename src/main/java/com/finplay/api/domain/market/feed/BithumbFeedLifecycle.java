package com.finplay.api.domain.market.feed;

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
		try {
			bithumbFeedClient.start();
		} catch (Exception e) {
			log.error("빗썸 시세 피드 시작 실패 — 시세 기능만 저하된 상태로 기동을 계속합니다.", e);
		}
	}

	@PreDestroy
	public void stopFeed() {
		log.info("빗썸 시세 피드 종료");
		bithumbFeedClient.stop();
	}
}
