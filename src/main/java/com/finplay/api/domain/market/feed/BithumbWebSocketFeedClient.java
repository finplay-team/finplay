// 빗썸 공개 WebSocket에 연결해 코인 실시간 체결 틱을 구독·수신하는 운영 전용 BithumbFeedClient 구현체
package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.CryptoCandleStore;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
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

// ticker 채널(현재가·주문 트리거용)에 더해 transaction 채널(체결 단위 가격·수량·시각, MKT-010 1분봉 집계용)도
// 같은 연결에 구독한다. 두 채널 모두 2026-08-06 실제 빗썸 연결로 필드 구성을 확인했다(이슈 #242) — 더 이상
// Decision Gate가 아니다. 응답 메시지 파싱은 각각 BithumbTickerMessageParser·BithumbTransactionMessageParser
// 한 곳에만 있다. 재연결 지수 백오프·구독 실패 처리는 PR #110 리뷰로 확정된 로직이다.
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class BithumbWebSocketFeedClient extends TextWebSocketHandler implements BithumbFeedClient {

	private static final URI BITHUMB_WS_URI = URI.create("wss://pubwss.bithumb.com/pub/ws");
	private static final String KRW_SUFFIX = "_KRW";
	private static final String SUBSCRIBE_TYPE_TICKER = "ticker";
	private static final String SUBSCRIBE_TYPE_TRANSACTION = "transaction";
	private static final List<String> SUBSCRIBE_TICK_TYPES = List.of("30M");
	// 재연결은 5초에서 시작해 실패마다 2배씩 늘어나 60초에서 캡된다(지수 백오프) — 연결에 성공하면 다음 끊김을
	// 위해 5초로 리셋된다(PR #110 리뷰 권장사항). 장애가 길어져도 무기한 짧은 간격 재시도로 로그가 폭증하지 않는다.
	private static final long RECONNECT_DELAY_MIN_SECONDS = 5;
	private static final long RECONNECT_DELAY_MAX_SECONDS = 60;

	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final CryptoCandleStore candleStore;
	private final ObjectMapper objectMapper;
	// 필드 초기화자 대신 생성자로 주입받는다 — @RequiredArgsConstructor를 유지하며(SpotBugs EI_EXPOSE_REP2 회피 패턴,
	// ai/agent-mistakes.md 2026-07-29) 재연결 경로(끊김→DISCONNECTED→재연결 예약, MKT-004)를 목(mock)
	// ScheduledExecutorService로 단위 테스트할 수 있게 한다(PR #110 리뷰 권장사항). 운영 빈 등록은 BithumbFeedConfig가 담당.
	private final StandardWebSocketClient webSocketClient;
	private final ScheduledExecutorService reconnectExecutor;
	private final Clock clock;

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
	// priceStore 호출을 try/catch로 감싼다(PR #296 재리뷰 참고사항) — onDisconnected()와 같은 이유다. Redis
	// 장애 중 종료되면 이 호출이 예외를 던지는데, 감싸지 않으면 @PreDestroy 훅(BithumbFeedLifecycle.stopFeed)
	// 밖으로 예외가 새 애플리케이션 종료를 방해할 수 있다.
	@Override
	public void stop() {
		running = false;
		reconnectExecutor.shutdownNow();
		closeQuietly(session, CloseStatus.NORMAL);
		session = null;
		try {
			priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		} catch (Exception e) {
			log.warn("종료 시 연결상태 기록 실패(Redis 장애로 추정) — 종료는 계속 진행합니다.", e);
		}
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
		String payload = message.getPayload();
		BithumbTickerMessageParser.parse(objectMapper, payload)
			.ifPresent(tick -> priceStore.saveTick(tick.symbol(), tick.price(), tick.receivedAt()));
		// transaction 체결도 현재가를 갱신한다(의도된 개선, plan.md "왜 transaction도 현재가를 갱신하는가") —
		// ticker 단독 수신 공백이 실측 최대 6~7초로 PriceStore의 stale 기준(10초)에 여유가 얇았다(이슈 #242).
		// saveTick은 과거 수신시각을 무시하므로(MKT-003) 두 채널이 함께 써도 값이 어긋나지 않는다.
		BithumbTransactionMessageParser.parse(objectMapper, payload).forEach(trade -> {
			candleStore.recordTrade(trade.symbol(), trade.tradedAt(), trade.price(), trade.quantity());
			priceStore.saveTick(trade.symbol(), trade.price(), trade.tradedAt());
		});
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

	// priceStore.saveConnectionStatus 호출을 try/catch로 감싼다(PR #296 리뷰 권장사항 1번) — 이 메서드는
	// afterConnectionClosed와 connect()의 .exceptionally 두 곳에서 scheduleReconnect() 바로 앞에 호출된다.
	// Redis 장애 중에는 이 Redis 쓰기가 예외를 던지는데, 감싸지 않으면 두 호출부 모두 뒤따르는
	// scheduleReconnect()가 실행되지 못해(connect() 쪽은 CompletableFuture가 예외를 삼켜 로그조차 남지 않는다)
	// Redis가 복구돼도 프로세스 재시작 전까지 재연결이 영구히 멈춘다. 이슈 #288이 "장애 중에도 앱은 계속
	// 실행 중"인 상태를 새로 만들면서 실제로 밟히게 된 경로라, 그 전제("시세 기능만 저하")를 지키려면 여기도
	// 감싸야 한다.
	private void onDisconnected() {
		session = null;
		try {
			priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		} catch (Exception e) {
			log.warn("연결 끊김 상태 기록 실패(Redis 장애로 추정) — 재연결 예약은 계속 진행합니다.", e);
		}
	}

	// instrumentRepository 조회부터 전송까지 전부 try 블록 안에서 수행한다 — 조회 실패도 구독 실패와 동일하게
	// 다뤄야 하고, 구독 자체가 실패하면 물리적 소켓이 열려 있어도 연결상태를 CONNECTED로 남기지 않는다(연결은
	// 됐는데 틱이 안 오는 상태 방지, PR #110 리뷰 권장사항). 열려 있는 소켓은 정리하고 재연결을 예약한다.
	// ticker·transaction 두 구독을 같은 세션에 순서대로 보낸다(연결을 추가로 열지 않는다, 이슈 #242 실측
	// 확인). 둘 중 하나라도 실패하면 이 try 블록 하나로 같은 실패 경로를 탄다 — 반쪽만 구독된 상태로 두지
	// 않는다(plan.md "구독" 절).
	private void subscribe(WebSocketSession target) {
		try {
			// 샌드박스(튜토리얼) 종목을 구독 목록에서 뺀다 (이슈 #528). 2026-08-22 실측으로는 이쪽이
			// REST 폴러만큼 급하지 않다 — 빗썸 웹소켓은 미등록 심볼이 섞인 구독도 "Filter Registered
			// Successfully"로 받고 실제 심볼의 틱을 그대로 보내 준다. 그래도 존재하지 않는 심볼을 계속
			// 구독 요청으로 실어 보낼 이유가 없고, 두 진입점의 조회 조건이 갈려 있으면 다음 사람이
			// "왜 여긴 다르지"를 다시 파야 한다.
			List<String> plainSymbols = instrumentRepository
				.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO)
				.stream()
				.map(Instrument::getSymbol)
				.toList();
			List<String> marketSymbols = plainSymbols.stream().map(symbol -> symbol + KRW_SUFFIX).toList();

			String tickerPayload = objectMapper.writeValueAsString(
				new TickerSubscribeRequest(SUBSCRIBE_TYPE_TICKER, marketSymbols, SUBSCRIBE_TICK_TYPES));
			target.sendMessage(new TextMessage(tickerPayload));

			String transactionPayload = objectMapper.writeValueAsString(
				new TransactionSubscribeRequest(SUBSCRIBE_TYPE_TRANSACTION, marketSymbols));
			target.sendMessage(new TextMessage(transactionPayload));

			// 이 연결이 성립된 시점 이후만 우리 분봉 데이터가 연속적으로 신뢰 가능하다는 워터마크를 심는다
			// (plan.md "since 워터마크"). 재연결·재시작 직후에는 이 시점부터가 신뢰 구간이라는 뜻이다.
			LocalDateTime now = LocalDateTime.now(clock);
			plainSymbols.forEach(symbol -> candleStore.touchSince(symbol, now));
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

	private record TickerSubscribeRequest(String type, List<String> symbols, List<String> tickTypes) {

		// 컬렉션 필드는 방어적 복사로 불변화한다 (SpotBugs EI_EXPOSE_REP/REP2 회피, ai/agent-mistakes.md 2026-07-29).
		private TickerSubscribeRequest {
			symbols = List.copyOf(symbols);
			tickTypes = List.copyOf(tickTypes);
		}
	}

	// transaction 구독 요청은 tickTypes가 없다(실측 확인, 이슈 #242) — ticker와 필드 구성이 다르다.
	private record TransactionSubscribeRequest(String type, List<String> symbols) {

		private TransactionSubscribeRequest {
			symbols = List.copyOf(symbols);
		}
	}
}
