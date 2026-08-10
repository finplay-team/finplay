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

	// bithumbFeedClient.start()가 던지는 예외를 여기서 삼킨다(이슈 #288) — 이 메서드는 ApplicationReadyEvent
	// 리스너라 예외가 새면 SpringApplication.run() 자체가 실패해 시세와 무관한 API까지 전부 죽는다. 실제로
	// FakeBithumbFeedClient.start()는 PriceStore.saveConnectionStatus로 Redis를 동기 호출하는데, Redis가
	// 죽어 있으면 그 예외가 바로 이 지점까지 전파된다. 시세는 캐시성 의존(Redis) 하나 때문에 전체 기동이
	// 막힐 만큼 핵심적이지 않다 — 이 기능만 저하된 채로 기동을 계속한다.
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
