// 실제 MySQL·인증 필터·MockMvc 비동기 SSE 경로를 통해 /api/stocks/stream 구독 시 snapshot이 먼저,
// 이후 매분 갱신 시 price 이벤트가 순서대로 도착하는지 검증하는 통합 테스트다 (이슈 #19 핵심 시나리오).
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// SseEmitter는 완료되지 않는 한 MockMvc의 asyncDispatch가 발생하지 않는다 — MvcResult.getAsyncResult()는 내부적으로
// 그 디스패치를 spring.mvc.async.request-timeout=-1 설정 그대로 무한정 기다리므로 이 클래스에서는 절대 호출하지 않는다.
// 혹시 모를 회귀에 대비해 테스트 타임아웃도 걸어둔다.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Timeout(60)
class StockPriceStreamIntegrationTest {

	// 컨텍스트 기동 시점(다른 날짜) 기준선은 이번 테스트의 서비스 날짜와 무관해, 이 날짜에서는 모든 종목이
	// UNAVAILABLE 기준선으로 시작한다 — 09:01에 신규 공개된 가격이 "변경"으로 정확히 감지되는지 검증할 수 있다.
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
	private static final LocalDateTime BEFORE_OPEN = LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0));
	private static final LocalDateTime AFTER_FIRST_CANDLE_CLOSES = LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 1));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockPriceStreamService stockPriceStreamService;

	@Autowired
	private SseEmitterRegistry sseEmitterRegistry;

	@Autowired
	private TestClock clock;

	@Test
	void subscriptionReceivesSnapshotFirstThenPriceEventAfterScheduledPublishRevealsNewPrice() throws Exception {
		clock.set(BEFORE_OPEN);
		stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(SERVICE_DATE, SERVICE_DATE, LocalDateTime.now(), LocalDateTime.now()));
		Instrument instrument = createStockInstrument("SSEFLOW");
		String symbol = instrument.getSymbol();
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, SERVICE_DATE, LocalTime.of(9, 0),
			new BigDecimal("70000"), new BigDecimal("70500"), new BigDecimal("69900"), new BigDecimal("70300"),
			1000L, "TEST", LocalDateTime.now()));
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(), "password-hash", uniqueNickname(), LocalDateTime.now()));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		MvcResult subscribeResult = mockMvc.perform(get("/api/stocks/stream")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(request().asyncStarted())
			.andReturn();
		// SseEmitter는 이 테스트에서 의도적으로 완료시키지 않는 한 asyncDispatch가 발생하지 않는다.
		// MvcResult.getAsyncResult()는 그 디스패치를 spring.mvc.async.request-timeout=-1 설정 그대로
		// 무한정(Long.MAX_VALUE ms) 기다리므로 여기서는 절대 호출하지 않는다 — 대신 실제 싱글턴
		// SseEmitterRegistry에 방금 등록된 emitter를 직접 조회한다.
		assertThat(registeredEmitters()).hasSize(1);
		String contentAfterSubscribe = subscribeResult.getResponse().getContentAsString();

		// 구독 직후에는 snapshot만 도착하고, 아직 09:00 이전이라 이 종목은 price 이벤트를 받은 적이 없다.
		assertThat(contentAfterSubscribe).contains("event:snapshot");
		assertThat(contentAfterSubscribe).doesNotContain("event:price");

		// 매분 스케줄러가 하는 일을 직접 호출해 09:01(첫 분봉 마감) 시각으로 갱신을 재현한다.
		clock.set(AFTER_FIRST_CANDLE_CLOSES);
		stockPriceStreamService.publishScheduledUpdates();

		String contentAfterPublish = subscribeResult.getResponse().getContentAsString();
		int snapshotIndex = contentAfterPublish.indexOf("event:snapshot");
		int priceIndex = contentAfterPublish.indexOf("event:price");
		assertThat(snapshotIndex).isGreaterThanOrEqualTo(0);
		assertThat(priceIndex).isGreaterThan(snapshotIndex);
		assertThat(contentAfterPublish).contains("\"symbol\":\"" + symbol + "\"");
		assertThat(contentAfterPublish).contains("id:STOCK:" + symbol + ":" + SERVICE_DATE.toString().replace("-", "")
			+ "0900");

		// emitter 정리(onCompletion/onTimeout/onError 콜백)는 SseEmitterRegistryTest가 실제 프레임워크 콜백
		// 경로(SseEmitterTestHandler)로 이미 단위 검증한다. emitter.complete()를 여기서 호출해도 MockMvc가 실제
		// async 디스패치를 재수행하기 전까지는 완료 콜백이 동기적으로 발화하지 않는다 — 재확인하려면
		// asyncDispatch(mvcResult)가 필요한데, 그 경로는 내부적으로 getAsyncResult()를 호출해
		// spring.mvc.async.request-timeout=-1 설정에서 사실상 무한 대기로 이어질 위험이 있어 이 통합 테스트에서는
		// 시도하지 않는다. register()가 반환한 emitter가 그대로 컨트롤러 응답으로 이어지는지는
		// StockPriceSseControllerTest에서 별도로 고정한다.
	}

	private List<SseEmitter> registeredEmitters() {
		return sseEmitterRegistry.getEmitters(Market.STOCK);
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 4);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true,
				LocalDateTime.now()));
	}

	private static String uniqueEmail() {
		return "sse-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname() {
		return "sse-" + UUID.randomUUID().toString().replace("-", "");
	}

}
