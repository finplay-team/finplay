// 빗썸 공개 WebSocket에 연결해 코인 실시간 체결 틱을 구독·수신하는 운영 전용 BithumbFeedClient 구현체
package com.finplay.api.market.feed;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;
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
// 교정하면 된다.
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class BithumbWebSocketFeedClient extends TextWebSocketHandler implements BithumbFeedClient {

	private static final URI BITHUMB_WS_URI = URI.create("wss://pubwss.bithumb.com/pub/ws");
	private static final String KRW_SUFFIX = "_KRW";
	private static final String SUBSCRIBE_TYPE_TICKER = "ticker";
	private static final List<String> SUBSCRIBE_TICK_TYPES = List.of("30M");
	private static final long RECONNECT_DELAY_SECONDS = 5;

	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final ObjectMapper objectMapper;
	private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

	private volatile boolean running;
	private volatile WebSocketSession session;

	@Override
	public void start() {
		running = true;
		connect();
	}

	@Override
	@PreDestroy
	public void stop() {
		running = false;
		reconnectExecutor.shutdownNow();
		WebSocketSession currentSession = session;
		if (currentSession != null && currentSession.isOpen()) {
			try {
				currentSession.close(CloseStatus.NORMAL);
			} catch (IOException ex) {
				log.warn("빗썸 WebSocket 종료 중 오류가 발생했습니다.", ex);
			}
		}
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
		reconnectExecutor.schedule(this::connect, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	private void onDisconnected() {
		session = null;
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
	}

	private void subscribe(WebSocketSession target) {
		List<String> symbols = instrumentRepository
			.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)
			.stream()
			.map(Instrument::getSymbol)
			.map(symbol -> symbol + KRW_SUFFIX)
			.toList();
		try {
			String payload = objectMapper.writeValueAsString(
				new SubscribeRequest(SUBSCRIBE_TYPE_TICKER, symbols, SUBSCRIBE_TICK_TYPES));
			target.sendMessage(new TextMessage(payload));
		} catch (Exception ex) {
			log.warn("빗썸 구독 메시지 전송에 실패했습니다.", ex);
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
