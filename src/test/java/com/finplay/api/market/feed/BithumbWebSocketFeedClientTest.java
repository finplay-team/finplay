// 목 WebSocketSession·PriceStore·InstrumentRepository로 BithumbWebSocketFeedClient의 콜백 기반 생명주기(연결·종료·메시지 수신)와
// 재연결 지수 백오프·구독 실패 처리(목 StandardWebSocketClient·ScheduledExecutorService)를 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
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
	private WebSocketSession session;

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
			instrumentRepository, priceStore, new ObjectMapper(), webSocketClient, reconnectExecutor);
	}

	@Test
	@DisplayName("연결 성공 시 PriceStore에 CONNECTED 상태를 저장하고 시딩된 종목으로 구독 메시지를 전송한다")
	void afterConnectionEstablishedSavesConnectedStatusAndSubscribes() throws Exception {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(
				Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, LocalDateTime.now())));

		client.afterConnectionEstablished(session);

		verify(priceStore, times(1)).saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		verify(session, times(1)).sendMessage(any(TextMessage.class));
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
	@DisplayName("구독 확인 등 ticker가 아닌 메시지는 saveTick을 호출하지 않는다")
	void handleTextMessageIgnoresNonTickerPayload() {
		String subscribeAck = """
			{ "status": "0000", "resmsg": "Filter Registered Successfully" }
			""";

		client.handleTextMessage(session, new TextMessage(subscribeAck));

		verify(priceStore, never()).saveTick(any(), any(), any());
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
