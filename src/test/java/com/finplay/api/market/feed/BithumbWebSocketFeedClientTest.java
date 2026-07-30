// 목 WebSocketSession·PriceStore·InstrumentRepository로 BithumbWebSocketFeedClient의 콜백 기반 생명주기(연결·종료·메시지 수신)를 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BithumbWebSocketFeedClientTest {

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private PriceStore priceStore;

	@Mock
	private WebSocketSession session;

	private BithumbWebSocketFeedClient client;

	@BeforeEach
	void setUp() {
		// 실제 운영 코드가 쓰는 것과 동일한 Jackson 3(tools.jackson) 계열 ObjectMapper를 그대로 사용한다 — 구독 메시지 직렬화·ticker
		// 메시지 역직렬화 모두 실제 동작으로 검증하기 위함(mock ObjectMapper stubbing으로 대체하지 않음).
		client = new BithumbWebSocketFeedClient(instrumentRepository, priceStore, new ObjectMapper());
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
		// 실제 WebSocketSession은 close() 이후 isOpen()이 false로 바뀐다 — mock에서 호출 순서에 따라 그 변화를 흉내낸다
		// (stop() 내부의 close 여부 판단용 첫 호출은 true, stop() 이후 isConnected()가 참조하는 두 번째 호출은 false).
		when(session.isOpen()).thenReturn(true, false);
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
}
