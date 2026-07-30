// 빗썸 공개 WebSocket에 연결해 코인 실시간 체결 틱을 구독·수신하는 운영 전용 BithumbFeedClient 구현체
package com.finplay.api.market.feed;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

// 구독 요청 JSON({"type":"ticker","symbols":["BTC_KRW",...],"tickTypes":["30M"]})과 ticker 응답 메시지의 정확한 필드
// 구성은 실제 빗썸 연결로 확인된 적이 없는 미확정 항목이다(Decision Gate — KisHistoricalCandleClientImpl의 output2
// 필드명과 같은 성격, spec.md·plan.md·tasks.md 이슈 #104 참고). 공개 문서 기준 최선 추정으로 구현했고, 응답 메시지
// 파싱은 BithumbTickerMessageParser 한 곳에만 있다 — 실제 연결 검증(외부 스모크)에서 필드가 다르면 그 클래스만
// 교정하면 된다. 재연결 지수 백오프·구독 실패 처리는 Decision Gate가 아니라 이번 PR(#110) 리뷰로 바로 확정된 로직이다.
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class BithumbWebSocketFeedClient extends TextWebSocketHandler implements BithumbFeedClient {

	private static final URI BITHUMB_WS_URI = URI.create("wss://pubwss.bithumb.com/pub/ws");
	private static final String KRW_SUFFIX = "_KRW";
	private static final String SUBSCRIBE_TYPE_TICKER = "ticker";
	private static final List<String> SUBSCRIBE_TICK_TYPES = List.of("30M");
	// 재연결은 5초에서 시작해 실패마다 2배씩 늘어나 60초에서 캡된다(지수 백오프) — 연결에 성공하면 다음 끊김을
	// 위해 5초로 리셋된다(PR #110 리뷰 권장사항). 장애가 길어져도 무기한 짧은 간격 재시도로 로그가 폭증하지 않는다.
	private static final long RECONNECT_DELAY_MIN_SECONDS = 5;
	private static final long RECONNECT_DELAY_MAX_SECONDS = 60;

	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final ObjectMapper objectMapper;
	// 필드 초기화자 대신 생성자로 주입받는다 — @RequiredArgsConstructor를 유지하며(SpotBugs EI_EXPOSE_REP2 회피 패턴,
	// docs/agent-mistakes.md 2026-07-29) 재연결 경로(끊김→DISCONNECTED→재연결 예약, MKT-004)를 목(mock)
	// ScheduledExecutorService로 단위 테스트할 수 있게 한다(PR #110 리뷰 권장사항). 운영 빈 등록은 BithumbFeedConfig가 담당.
	private final StandardWebSocketClient webSocketClient;
	private final ScheduledExecutorService reconnectExecutor;

	private volatile boolean running;
	private volatile WebSocketSession session;
	private volatile long reconnectDelaySeconds = RECONNECT_DELAY_MIN_SECONDS;

	@Override
	public void start() {
		running = true;
		connect();
	}

	// BithumbFeedLifecycle이 애플리케이션 종료 시 이 메서드를 호출하는 유일한 지점이다 — 여기 @PreDestroy를
	// 붙이면 컨테이너가 두 번 호출하게 되어 그 단일 지점 원칙이 깨진다(리뷰 권장사항, 이슈 #104).
	@Override
	public void stop() {
		running = false;
		reconnectExecutor.shutdownNow();
		closeQuietly(session, CloseStatus.NORMAL);
		session = null;
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
	}

	@Override
	public boolean isConnected() {
		WebSocketSession currentSession = session;
		return currentSession != null && currentSession.isOpen();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession newSession) {
		session = newSession;
		reconnectDelaySeconds = RECONNECT_DELAY_MIN_SECONDS;
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		log.info("빗썸 WebSocket 연결에 성공했습니다.");
		subscribe(newSession);
	}

	@Override
	public void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) {
		BithumbTickerMessageParser.parse(objectMapper, message.getPayload())
			.ifPresent(tick -> priceStore.saveTick(tick.symbol(), tick.price(), tick.receivedAt()));
	}

	@Override
	public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
		log.warn("빗썸 WebSocket 전송 오류가 발생했습니다.", exception);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus closeStatus) {
		log.warn("빗썸 WebSocket 연결이 종료됐습니다 (status={}).", closeStatus);
		onDisconnected();
		scheduleReconnect();
	}

	private void connect() {
		if (!running) {
			return;
		}
		webSocketClient
			.execute(this, new WebSocketHttpHeaders(), BITHUMB_WS_URI)
			.exceptionally(ex -> {
				log.warn("빗썸 WebSocket 연결에 실패했습니다.", ex);
				onDisconnected();
				scheduleReconnect();
				return null;
			});
	}

	private void scheduleReconnect() {
		if (!running || reconnectExecutor.isShutdown()) {
			return;
		}
		long delay = reconnectDelaySeconds;
		reconnectExecutor.schedule(this::connect, delay, TimeUnit.SECONDS);
		reconnectDelaySeconds = Math.min(delay * 2, RECONNECT_DELAY_MAX_SECONDS);
	}

	private void onDisconnected() {
		session = null;
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
	}

	// instrumentRepository 조회부터 전송까지 전부 try 블록 안에서 수행한다 — 조회 실패도 구독 실패와 동일하게
	// 다뤄야 하고, 구독 자체가 실패하면 물리적 소켓이 열려 있어도 연결상태를 CONNECTED로 남기지 않는다(연결은
	// 됐는데 틱이 안 오는 상태 방지, PR #110 리뷰 권장사항). 열려 있는 소켓은 정리하고 재연결을 예약한다.
	private void subscribe(WebSocketSession target) {
		try {
			List<String> symbols = instrumentRepository
				.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)
				.stream()
				.map(Instrument::getSymbol)
				.map(symbol -> symbol + KRW_SUFFIX)
				.toList();
			String payload = objectMapper.writeValueAsString(
				new SubscribeRequest(SUBSCRIBE_TYPE_TICKER, symbols, SUBSCRIBE_TICK_TYPES));
			target.sendMessage(new TextMessage(payload));
		} catch (Exception ex) {
			log.warn("빗썸 구독에 실패해 연결을 재시도합니다.", ex);
			closeQuietly(target, CloseStatus.SERVER_ERROR);
			onDisconnected();
			scheduleReconnect();
		}
	}

	private void closeQuietly(WebSocketSession target, CloseStatus closeStatus) {
		if (target != null && target.isOpen()) {
			try {
				target.close(closeStatus);
			} catch (IOException ex) {
				log.warn("빗썸 WebSocket 종료 중 오류가 발생했습니다.", ex);
			}
		}
	}

	private record SubscribeRequest(String type, List<String> symbols, List<String> tickTypes) {

		// 컬렉션 필드는 방어적 복사로 불변화한다 (SpotBugs EI_EXPOSE_REP/REP2 회피, docs/agent-mistakes.md 2026-07-29).
		private SubscribeRequest {
			symbols = List.copyOf(symbols);
			tickTypes = List.copyOf(tickTypes);
		}
	}
}
