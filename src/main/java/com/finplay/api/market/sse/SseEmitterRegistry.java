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
	// 계약: 이 반환값은 반드시 호출한 컨트롤러 메서드의 반환값으로 그대로 돌려줘야 한다. 그렇지 않으면 Spring이
	// 비동기 요청 처리를 시작하지 않아 emitter가 완료·타임아웃 콜백 없이 이 집합에 영구히 남는다 — register()가
	// 쓰는 무제한 timeout의 SseEmitter는 자동 회수 경로가 없다.
	public SseEmitter register(Market market) {
		SseEmitter emitter = createEmitter(market);
		activate(market, emitter);
		return emitter;
	}

	// emitter를 생성하고 onCompletion/onTimeout/onError 콜백을 배선한 뒤 retry: 3000을 1회 전송하되, market의 활성
	// 브로드캐스트 집합에는 아직 추가하지 않는다. 호출자가 이 emitter 하나에만 초기 데이터(snapshot 등)를 먼저 보낸
	// 뒤 activate()로 집합에 넣으면, 그 사이 진행 중인 매분 브로드캐스트가 아직 활성화되지 않은 이 emitter를 대상에
	// 포함할 수 없어 "초기 데이터보다 broadcast가 먼저 도착"하는 경합이 원천적으로 생기지 않는다 (PR #94 후속 리뷰 —
	// 락으로 상호배제하는 대신 등록 순서를 바꿔 경합 자체를 없앤다).
	public SseEmitter createEmitter(Market market) {
		SseEmitter emitter = new SseEmitter();
		CopyOnWriteArrayList<SseEmitter> emitters = emittersByMarket.get(market);

		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(throwable -> emitters.remove(emitter));

		sendRetryHint(emitter, emitters);
		return emitter;
	}

	// createEmitter()로 만든 emitter를 market의 활성 브로드캐스트 집합에 추가한다. 이 시점 이후부터 heartbeat·
	// price·status broadcast(getEmitters() 순회)의 대상이 된다.
	public void activate(Market market, SseEmitter emitter) {
		emittersByMarket.get(market).add(emitter);
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
				} catch (IOException | RuntimeException e) {
					// complete()된 emitter에 send()를 호출하면 IOException이 아니라 검사되지 않는
					// IllegalStateException이 발생한다(Assert.state(!this.complete, ...)) — complete()가
					// complete=true를 즉시 세팅하지만 컨테이너의 completion 콜백(emitters.remove())은 그 이후에
					// 실행되므로, 이 창에서 tick이 이 emitter를 다시 순회할 수 있다. RuntimeException까지 잡지
					// 않으면 EnumMap 순회 전체가 중단되어 다른 market 구독자의 heartbeat까지 끊긴다.
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
