package com.finplay.api.domain.market.sse;

import com.finplay.api.domain.market.entity.Market;
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

	public SseEmitter register(Market market) {
		SseEmitter emitter = createEmitter(market);
		activate(market, emitter);
		return emitter;
	}

	public SseEmitter createEmitter(Market market) {
		SseEmitter emitter = new SseEmitter();
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByMarket.get(market);

		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(throwable -> emitters.remove(emitter));

		sendRetryHint(emitter, emitters);
		return emitter;
	}

	public void activate(Market market, SseEmitter emitter) {
		emittersByMarket.get(market).add(emitter);
	}

	public List<SseEmitter> getEmitters(Market market) {
		return Collections.unmodifiableList(emittersByMarket.get(market));
	}

	@Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
	public void sendHeartbeat() {
		for (Map.Entry<Market, CopyOnWriteArrayList<SseEmitter>> entry : emittersByMarket.entrySet()) {
			CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();
			for (SseEmitter emitter : emitters) {
				try {
					emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
				} catch (IOException | RuntimeException e) {
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
		} catch (IOException | RuntimeException e) {
			log.debug("retry 힌트 전송 실패로 emitter 정리", e);
			emitters.remove(emitter);
			emitter.completeWithError(e);
		}
	}
}
