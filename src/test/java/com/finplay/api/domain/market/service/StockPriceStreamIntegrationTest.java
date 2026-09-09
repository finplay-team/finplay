package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Timeout(60)
class StockPriceStreamIntegrationTest {

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
		assertThat(registeredEmitters()).hasSize(1);
		String contentAfterSubscribe = subscribeResult.getResponse().getContentAsString();

		assertThat(contentAfterSubscribe).contains("event:snapshot");
		assertThat(contentAfterSubscribe).doesNotContain("event:price");

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
