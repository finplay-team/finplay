// STOCK·CRYPTO market별 SseEmitter 구독자 집합을 관리하고 retry 힌트·heartbeat·연결 종료 정리를 공통 처리하는 컴포넌트 (이슈 #18)
package com.finplay.api.market.sse;

import com.finplay.api.market.domain.Market;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SseEmitterRegistry {

	private static final long RETRY_MILLIS = 3000L;
	private static final long HEARTBEAT_INTERVAL_MILLIS = 20_000L;
	private static final String HEARTBEAT_COMMENT = "heartbeat";

	private final Map<Market, CopyOnWriteArrayList<SseEmitter>> emittersByMarket;

	public SseEmitterRegistry() {
		Map<Market, CopyOnWriteArrayList<SseEmitter>> initial = new EnumMap<>(Market.class);
		for (Market market : Market.values()) {
			initial.put(market, new CopyOnWriteArrayList<>());
		}
		this.emittersByMarket = initial;
	}

	// emitter를 생성해 market별 집합에 등록하고, 그 자리에서 retry: 3000을 1회 전송한다. 컨트롤러(#19·#20)는 이후 snapshot·price·status를 이 emitter로 push한다.
	public SseEmitter register(Market market) {
		SseEmitter emitter = new SseEmitter();
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByMarket.get(market);
		emitters.add(emitter);

		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(throwable -> emitters.remove(emitter));

		sendRetryHint(emitter, emitters);
		return emitter;
	}

	// 테스트·모니터링용 — 현재 market에 등록된 emitter 목록을 그대로 비추는 읽기 전용 뷰(등록·해제가 즉시 반영됨, 스냅샷 아님).
	public List<SseEmitter> getEmitters(Market market) {
		return Collections.unmodifiableList(emittersByMarket.get(market));
	}

	// 20초마다 등록된 모든 market의 모든 emitter에 SSE 주석(": heartbeat\n\n")을 전송해 프록시·브라우저 타임아웃을 방지한다.
	@Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
	public void sendHeartbeat() {
		for (Map.Entry<Market, CopyOnWriteArrayList<SseEmitter>> entry : emittersByMarket.entrySet()) {
			CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
			for (SseEmitter emitter : emitters) {
				try {
					emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
				} catch (IOException e) {
					log.debug("heartbeat 전송 실패로 emitter 정리: market={}", entry.getKey(), e);
					emitters.remove(emitter);
					emitter.completeWithError(e);
				}
			}
		}
	}

	private void sendRetryHint(SseEmitter emitter, CopyOnWriteArrayList<SseEmitter> emitters) {
		try {
			emitter.send(SseEmitter.event().reconnectTime(RETRY_MILLIS));
		} catch (IOException e) {
			log.debug("retry 힌트 전송 실패로 emitter 정리", e);
			emitters.remove(emitter);
			emitter.completeWithError(e);
		}
	}
}
