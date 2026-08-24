// Mock HTTP로 BithumbRestTickerPoller의 markets 조합·trade_price 매핑·부분 이상 항목 skip·장애 시 무전파를 검증한다 (이슈 #107 ⑫, #369)
package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class BithumbRestTickerPollerTest {

	private static final String ENDPOINT = "https://api.bithumb.com/v1/ticker";
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 31, 10, 0, 0);

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private PriceStore priceStore;

	private MockRestServiceServer server;
	private BithumbRestTickerPoller poller;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		Clock clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		poller = new BithumbRestTickerPoller(builder.build(), instrumentRepository, priceStore, clock);
	}

	private static Instrument crypto(String symbol) {
		return Instrument.create(Market.CRYPTO, symbol, symbol + "코인", BigDecimal.ONE, 1000, true, FIXED_NOW);
	}

	private void givenCryptoInstruments(String... symbols) {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(symbols).stream().map(BithumbRestTickerPollerTest::crypto).toList());
	}

	// 실제 응답에는 우리가 안 쓰는 필드가 더 많다 — @JsonIgnoreProperties(ignoreUnknown=true)까지 함께 고정한다.
	private static String tickerItem(String market, String tradePrice) {
		return """
			{"market": "%s", "trade_price": %s, "opening_price": 1, "high_price": 2, "acc_trade_volume": 3}
			""".formatted(market, tradePrice);
	}

	// --- 요청 조합: 코인 전체를 콤마로 묶어 1회만 호출 ---

	@Test
	@DisplayName("코인 종목 전체를 KRW-{symbol} 콤마 결합으로 묶어 ticker를 정확히 1회 호출한다")
	void pollTickersCallsTickerEndpointOnceWithCommaJoinedKrwMarkets() {
		givenCryptoInstruments("BTC", "ETH", "XRP");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andExpect(method(HttpMethod.GET))
			.andExpect(queryParam("markets", "KRW-BTC,KRW-ETH,KRW-XRP"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		poller.pollTickers();

		// 기대는 once()가 기본이므로 2회 호출되면 verify가 아니라 호출 시점에 AssertionError로 드러난다.
		server.verify();
	}

	// 이슈 #528 — markets에 샌드박스 종목이 실려 나가지 않는 것을 조회 선택으로 고정한다. 실제 필터링은 쿼리가
	// 하므로(InstrumentRepositoryTest가 검증) 여기서는 "샌드박스를 거르는 조회를 쓰는가"만 본다.
	// 조회를 통째로 옛것으로 되돌리면 이 파일의 다른 테스트들이 먼저 깨진다 — **이 테스트만 고유하게 막는 것은
	// 새 조회와 옛 조회를 함께 부르는 구현**이고, 그게 아래 never() 단정의 몫이다.
	@Test
	@DisplayName("샌드박스를 거르지 않는 옛 조회로는 markets를 만들지 않는다")
	void pollTickersUsesSandboxExcludingQueryOnly() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andExpect(queryParam("markets", "KRW-BTC"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		poller.pollTickers();

		server.verify();
		verify(instrumentRepository).findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO);
		verify(instrumentRepository, never()).findByMarketAndTradableTrueOrderByIdAsc(any(Market.class));
	}

	@Test
	@DisplayName("코인 종목이 하나도 없으면 HTTP 호출 자체를 하지 않는다")
	void pollTickersDoesNotCallHttpWhenNoCryptoInstruments() {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of());

		poller.pollTickers();

		// 기대를 하나도 등록하지 않았으므로, 호출이 있었다면 그 시점에 실패한다.
		server.verify();
		verifyNoInteractions(priceStore);
	}

	// --- 응답 매핑: KRW- 접두사 제거 + trade_price 그대로, PriceStore.recordObservation으로 전달 ---

	@Test
	@DisplayName("응답 항목마다 KRW- 접두사를 뗀 심볼과 trade_price로 PriceStore.recordObservation을 호출한다")
	void pollTickersRecordsObservationPerItemWithStrippedSymbolAndTradePrice() {
		givenCryptoInstruments("BTC", "ETH");
		String body = "[" + tickerItem("KRW-BTC", "91234000") + "," + tickerItem("KRW-ETH", "4567000.5") + "]";
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		poller.pollTickers();

		ArgumentCaptor<BigDecimal> priceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
		verify(priceStore).recordObservation(eq("BTC"), priceCaptor.capture(), eq(FIXED_NOW));
		verify(priceStore).recordObservation(eq("ETH"), priceCaptor.capture(), eq(FIXED_NOW));
		assertThat(priceCaptor.getAllValues().get(0)).isEqualByComparingTo("91234000");
		assertThat(priceCaptor.getAllValues().get(1)).isEqualByComparingTo("4567000.5");
		// PriceStore의 다른 메서드(연결상태·과거틱 가드 등)는 건드리지 않는다 — recordObservation만 호출된다.
		verifyNoMoreInteractions(priceStore);
	}

	// --- 부분 이상 항목: 그 항목만 건너뛴다 ---

	@Test
	@DisplayName("trade_price가 없는 항목만 건너뛰고 나머지는 정상 관측한다")
	void pollTickersSkipsOnlyTheItemMissingTradePrice() {
		givenCryptoInstruments("BTC", "ETH");
		String body = "[{\"market\": \"KRW-BTC\"}," + tickerItem("KRW-ETH", "4567000") + "]";
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		poller.pollTickers();

		verify(priceStore, never()).recordObservation(eq("BTC"), any(), any());
		verify(priceStore).recordObservation(eq("ETH"), any(BigDecimal.class), eq(FIXED_NOW));
		verifyNoMoreInteractions(priceStore);
	}

	@Test
	@DisplayName("market이 KRW-로 시작하지 않는 항목만 건너뛰고 나머지는 정상 관측한다")
	void pollTickersSkipsOnlyTheItemWithNonKrwMarketCode() {
		givenCryptoInstruments("BTC", "ETH");
		String body = "[" + tickerItem("BTC_KRW", "91234000") + "," + tickerItem("KRW-ETH", "4567000") + "]";
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		poller.pollTickers();

		verify(priceStore).recordObservation(eq("ETH"), any(BigDecimal.class), eq(FIXED_NOW));
		verifyNoMoreInteractions(priceStore);
	}

	// --- 장애 처리: 예외를 밖으로 던지지 않고 그 회차를 건너뛴다 (스케줄러가 죽으면 안 된다) ---

	@Test
	@DisplayName("5xx 응답이면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsServerErrorWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT))).andRespond(withServerError());

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("4xx 응답이면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsClientErrorWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad request"));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("응답 본문이 null이면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsNullBodyWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("응답 본문이 비어 있으면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsEmptyBodyWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("빈 배열 응답이면 recordObservation을 호출하지 않는다")
	void pollTickersRecordsNothingForEmptyArrayResponse() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	// 2026-08-22 실측 응답을 그대로 고정한다 (이슈 #528). 빗썸은 미등록 market 코드가 하나라도 섞이면
	// **상태코드 200에 배열이 아닌 error 객체**를 돌려주고, 같이 요청한 정상 심볼의 시세도 주지 않는다. 상태코드
	// 가드(onStatus)로는 걸러지지 않고 역직렬화 단계에서 터진다 — 샌드박스 종목이 markets에 섞여 있던 동안
	// 이 폴러가 조용히 매 회차 실패한 경로가 이것이다.
	// 심볼 둘을 요청해 두는 것은 **부분 성공이 없다**는 것이 이 장애의 핵심이기 때문이다 — 미등록 코드 하나가
	// 배치 전체를 버리게 만든다. 그 구조 자체는 이 PR이 바꾸지 않았고(범위 밖), 지금은 목록에서 미등록 코드가
	// 빠졌을 뿐이다.
	@Test
	@DisplayName("상태코드 200이라도 본문이 빗썸 error 봉투면 요청에 실린 정상 심볼까지 하나도 관측되지 않는다")
	void pollTickersSwallowsBithumbErrorEnvelopeReturnedWithOkStatus() {
		givenCryptoInstruments("BTC", "ETH");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andExpect(queryParam("markets", "KRW-BTC,KRW-ETH"))
			.andRespond(withSuccess("{\"error\":{\"name\":404,\"message\":\"Code not found\"}}",
				MediaType.APPLICATION_JSON));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("JSON 파싱이 불가능하면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsMalformedJsonWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}

	@Test
	@DisplayName("연결 실패(RestClientException)면 예외를 전파하지 않고 recordObservation도 호출하지 않는다")
	void pollTickersSwallowsConnectionFailureWithoutRecordingObservation() {
		givenCryptoInstruments("BTC");
		server.expect(requestTo(Matchers.startsWith(ENDPOINT)))
			.andRespond(request -> {
				throw new IOException("connection timed out");
			});

		assertThatCode(() -> poller.pollTickers()).doesNotThrowAnyException();

		verifyNoInteractions(priceStore);
	}
}
