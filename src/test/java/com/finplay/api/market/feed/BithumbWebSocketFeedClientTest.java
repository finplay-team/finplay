// 목 WebSocketSession·PriceStore·InstrumentRepository로 BithumbWebSocketFeedClient의 콜백 기반 생명주기(연결·종료·메시지 수신)와
// 재연결 지수 백오프·구독 실패 처리(목 StandardWebSocketClient·ScheduledExecutorService)를 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.CryptoCandleStore;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BithumbWebSocketFeedClientTest {

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private PriceStore priceStore;

	@Mock
	private CryptoCandleStore candleStore;

	@Mock
	private WebSocketSession session;

	private final Clock clock = Clock.fixed(
		LocalDateTime.of(2026, 8, 6, 15, 37, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

	// 재연결 경로(끊김→DISCONNECTED→재연결 예약, MKT-004)를 목으로 검증하기 위해 생성자로 주입한다(PR #110 리뷰
	// 권장사항 — 필드 초기화자 하드코딩이면 이 경로를 mock으로 검증할 수 없었다). start()를 호출하지 않는 테스트에서는
	// 스텁 없이 그대로 둔다.
	@Mock
	private StandardWebSocketClient webSocketClient;

	@Mock
	private ScheduledExecutorService reconnectExecutor;

	private BithumbWebSocketFeedClient client;

	@BeforeEach
	void setUp() {
		// 실제 운영 코드가 쓰는 것과 동일한 Jackson 3(tools.jackson) 계열 ObjectMapper를 그대로 사용한다 — 구독 메시지 직렬화·ticker
		// 메시지 역직렬화 모두 실제 동작으로 검증하기 위함(mock ObjectMapper stubbing으로 대체하지 않음).
		client = new BithumbWebSocketFeedClient(
			instrumentRepository, priceStore, candleStore, new ObjectMapper(), webSocketClient, reconnectExecutor,
			clock);
	}

	@Test
	@DisplayName("연결 성공 시 PriceStore에 CONNECTED 상태를 저장하고 ticker·transaction 두 구독 메시지를 전송하며 since 워터마크를 심는다")
	void afterConnectionEstablishedSavesConnectedStatusAndSubscribesBothChannels() throws Exception {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(
				Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, LocalDateTime.now())));

		client.afterConnectionEstablished(session);

		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		// ticker + transaction 두 건 — 연결을 추가로 열지 않고 같은 세션에 순서대로 보낸다(이슈 #242 실측).
		verify(session, times(2)).sendMessage(any(TextMessage.class));
		verify(candleStore, times(1)).touchSince("BTC", LocalDateTime.now(clock));
	}

	@Test
	@DisplayName("구독 메시지 중 첫 번째는 ticker, 두 번째는 transaction 타입이며 tickTypes가 없다")
	void subscribeSendsTickerThenTransactionWithDistinctPayloadShapes() throws Exception {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(
				Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, LocalDateTime.now())));
		ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);

		client.afterConnectionEstablished(session);

		verify(session, times(2)).sendMessage(messageCaptor.capture());
		List<TextMessage> sent = messageCaptor.getAllValues();
		assertThat(sent.get(0).getPayload()).contains("\"type\":\"ticker\"").contains("\"tickTypes\"");
		assertThat(sent.get(1).getPayload()).contains("\"type\":\"transaction\"").doesNotContain("tickTypes");
	}

	@Test
	@DisplayName("연결 종료 콜백 시 PriceStore에 DISCONNECTED 상태를 저장한다")
	void afterConnectionClosedSavesDisconnectedStatus() {
		client.afterConnectionClosed(session, CloseStatus.NORMAL);

		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
	}

	@Test
	@DisplayName("정상 ticker 페이로드 수신 시 PriceStore.saveTick이 심볼·가격·수신시각과 함께 호출된다")
	void handleTextMessageSavesTickForValidTickerPayload() {
		String payload = """
			{
			  "type": "ticker",
			  "content": {
			    "symbol": "BTC_KRW",
			    "closePrice": "52000000",
			    "date": "20260730",
			    "time": "153000"
			  }
			}
			""";

		client.handleTextMessage(session, new TextMessage(payload));

		verify(priceStore, times(1))
			.saveTick("BTC", new BigDecimal("52000000"), LocalDateTime.of(2026, 7, 30, 15, 30, 0));
	}

	@Test
	@DisplayName("정상 transaction 페이로드 수신 시 CryptoCandleStore.recordTrade와 PriceStore.saveTick이 함께 호출된다")
	void handleTextMessageRecordsTradeAndSavesTickForValidTransactionPayload() {
		String payload = """
			{
			  "type": "transaction",
			  "content": {
			    "list": [
			      {"symbol": "BTC_KRW", "contPrice": "91839000", "contQty": "0.00016332", "contDtm": "2026-08-06 15:37:00.000000"}
			    ]
			  }
			}
			""";

		client.handleTextMessage(session, new TextMessage(payload));

		LocalDateTime tradedAt = LocalDateTime.of(2026, 8, 6, 15, 37, 0);
		verify(candleStore, times(1)).recordTrade("BTC", tradedAt, new BigDecimal("91839000"),
			new BigDecimal("0.00016332"));
		verify(priceStore, times(1)).saveTick("BTC", new BigDecimal("91839000"), tradedAt);
	}

	@Test
	@DisplayName("list에 체결이 여러 건이면 전부 recordTrade·saveTick이 호출된다")
	void handleTextMessageProcessesEveryTradeInList() {
		String payload = """
			{
			  "type": "transaction",
			  "content": {
			    "list": [
			      {"symbol": "BTC_KRW", "contPrice": "91839000", "contQty": "0.001", "contDtm": "2026-08-06 15:37:00.000000"},
			      {"symbol": "BTC_KRW", "contPrice": "91840000", "contQty": "0.002", "contDtm": "2026-08-06 15:37:01.000000"}
			    ]
			  }
			}
			""";

		client.handleTextMessage(session, new TextMessage(payload));

		verify(candleStore, times(2)).recordTrade(eq("BTC"), any(), any(), any());
		verify(priceStore, times(2)).saveTick(eq("BTC"), any(), any());
	}

	@Test
	@DisplayName("구독 확인 등 ticker·transaction이 아닌 메시지는 saveTick·recordTrade 어느 것도 호출하지 않는다")
	void handleTextMessageIgnoresNonTickerPayload() {
		String subscribeAck = """
			{ "status": "0000", "resmsg": "Filter Registered Successfully" }
			""";

		client.handleTextMessage(session, new TextMessage(subscribeAck));

		verify(priceStore, never()).saveTick(any(), any(), any());
		verify(candleStore, never()).recordTrade(any(), any(), any(), any());
	}

	@Test
	@DisplayName("stop() 호출 후에는 세션이 종료되고 isConnected가 false를 반환한다")
	void stopClosesSessionAndMarksDisconnected() throws Exception {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)).thenReturn(List.of());
		when(session.isOpen()).thenReturn(true);
		client.afterConnectionEstablished(session);

		client.stop();

		verify(session, times(1)).close(CloseStatus.NORMAL);
		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		assertThat(client.isConnected()).isFalse();
	}

	// PR #296 재리뷰 참고사항: stop()도 onDisconnected()와 같은 이유로 Redis 장애에 견고해야 한다 — 감싸지
	// 않으면 @PreDestroy 훅(BithumbFeedLifecycle.stopFeed) 밖으로 예외가 새 애플리케이션 종료를 방해할 수 있다.
	@Test
	@DisplayName("종료 시 상태 기록이 Redis 장애로 실패해도 stop()은 예외 없이 끝난다 (PR #296 재리뷰 참고사항)")
	void stopDoesNotPropagateWhenSavingDisconnectedStatusFails() throws Exception {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)).thenReturn(List.of());
		when(session.isOpen()).thenReturn(true);
		client.afterConnectionEstablished(session);
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(priceStore)
			.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		assertThatCode(client::stop).doesNotThrowAnyException();

		verify(session, times(1)).close(CloseStatus.NORMAL);
		assertThat(client.isConnected()).isFalse();
	}

	@Test
	@DisplayName("연결이 없는 상태에서 isConnected는 false를 반환한다")
	void isConnectedReturnsFalseWhenNeverConnected() {
		assertThat(client.isConnected()).isFalse();
	}

	@Test
	@DisplayName("연결이 끊기면 재연결이 5초 뒤로 예약된다 (running=true인 동안, PR #110 리뷰 권장사항)")
	void afterConnectionClosedSchedulesReconnectWhileRunning() {
		stubSuccessfulConnectAttempt();
		client.start();

		client.afterConnectionClosed(session, CloseStatus.NORMAL);

		verify(reconnectExecutor, times(1)).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("stop() 이후에는 연결 종료 콜백이 와도 재연결이 예약되지 않는다 (PR #110 리뷰 권장사항)")
	void afterConnectionClosedDoesNotScheduleReconnectAfterStop() {
		stubSuccessfulConnectAttempt();
		client.start();
		client.stop();

		client.afterConnectionClosed(session, CloseStatus.NORMAL);

		verify(reconnectExecutor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
	}

	@Test
	@DisplayName("재연결 실패가 반복되면 지연이 5초→10초로 2배가 되고, 연결에 성공하면 다시 5초로 리셋된다")
	void reconnectDelayDoublesOnRepeatedFailureAndResetsAfterSuccessfulConnection() {
		stubSuccessfulConnectAttempt();
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)).thenReturn(List.of());
		client.start();

		client.afterConnectionClosed(session, CloseStatus.NORMAL);
		client.afterConnectionClosed(session, CloseStatus.NORMAL);
		client.afterConnectionEstablished(session);
		client.afterConnectionClosed(session, CloseStatus.NORMAL);

		ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
		verify(reconnectExecutor, times(3)).schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.SECONDS));
		assertThat(delayCaptor.getAllValues()).containsExactly(5L, 10L, 5L);
	}

	// PR #296 리뷰 권장사항 1번: Redis 장애 중에는 priceStore.saveConnectionStatus(DISCONNECTED)가 예외를
	// 던진다. onDisconnected() 안에서 감싸지 않으면 바로 다음 줄의 scheduleReconnect()가 실행되지 못해,
	// WebSocket 연결 자체와 무관한 Redis 장애 때문에 재연결이 영구히 멈춘다 — 이 테스트가 그 회귀를 막는다.
	@Test
	@DisplayName("연결 종료 시 상태 기록이 Redis 장애로 실패해도 재연결은 그대로 예약된다 (PR #296 리뷰 권장사항)")
	void afterConnectionClosedStillSchedulesReconnectWhenSavingDisconnectedStatusFails() {
		stubSuccessfulConnectAttempt();
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(priceStore)
			.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		client.start();

		client.afterConnectionClosed(session, CloseStatus.NORMAL);

		verify(reconnectExecutor, times(1)).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
	}

	// connect()의 .exceptionally도 같은 onDisconnected()를 거친다 — 초기 연결 시도 자체가 실패하는 경로에서도
	// 같은 회귀가 재현될 수 있어 별도로 확인한다.
	@Test
	@DisplayName("초기 연결 실패 시 상태 기록이 Redis 장애로 실패해도 재연결은 그대로 예약된다 (PR #296 리뷰 권장사항)")
	void connectExceptionallyStillSchedulesReconnectWhenSavingDisconnectedStatusFails() {
		when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
			.thenReturn(CompletableFuture.failedFuture(new IOException("연결 실패")));
		doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
			.when(priceStore)
			.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		client.start();

		verify(reconnectExecutor, times(1)).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("구독 전송이 실패하면 연결상태가 DISCONNECTED로 남고 재연결이 예약된다 (PR #110 리뷰 권장사항)")
	void subscribeFailureMarksDisconnectedAndSchedulesReconnect() throws Exception {
		stubSuccessfulConnectAttempt();
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO)).thenReturn(List.of());
		when(session.isOpen()).thenReturn(true);
		doThrow(new IOException("전송 실패")).when(session).sendMessage(any(TextMessage.class));
		client.start();

		client.afterConnectionEstablished(session);

		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);
		verify(session, times(1)).close(any(CloseStatus.class));
		verify(reconnectExecutor, times(1)).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
	}

	private void stubSuccessfulConnectAttempt() {
		when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
			.thenReturn(CompletableFuture.completedFuture(session));
	}
}
