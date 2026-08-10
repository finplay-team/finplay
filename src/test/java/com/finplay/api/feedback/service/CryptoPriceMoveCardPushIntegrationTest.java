// 코인 카드 확정(CryptoPriceMoveWatcher) → CryptoPriceMoveCardPublisher → Redis 채널까지 실 Redis로 이어지는지,
// 주식 카드 확정 경로(PriceMoveCardService)는 이 채널에 아무것도 보내지 않는지 검증한다.
// (docs/specs/026-crypto-card-sse-push/tasks.md 항목 3 — 통합 검증)
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.CryptoPriceMoveCardPublisher;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// LLM은 부르지 않는다 — application.yml의 api-key가 "not-configured"라 NarrativeService가 §템플릿 문장으로
// 폴백한다(CryptoPriceMoveWatcherIntegrationTest와 같은 전제). NewsCollector는 이 시나리오에서 근거가 첫
// 매칭에서 바로 잡혀 온디맨드 수집을 타지 않지만, 다른 통합 테스트와 같은 관례로 mock으로 갈아끼워 둔다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveCardPushIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String CRYPTO_SYMBOL = "CARDPUSHC";

	// V7 시드와 겹치지 않는 테스트 전용 주식 심볼.
	private static final String STOCK_SYMBOL = "CARDPUSHS";

	private static final LocalDate STOCK_ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	@MockitoBean
	private NewsCollector newsCollector;

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	@Autowired
	private PriceMoveCardService priceMoveCardService;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private TestClock clock;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private RedisMessageListenerContainer listenerContainer;

	private BlockingQueue<String> receivedPayloads;

	@BeforeEach
	void setUp() throws InterruptedException {
		clock.set(NOW);
		receivedPayloads = new ArrayBlockingQueue<>(10);
		listenerContainer = new RedisMessageListenerContainer();
		listenerContainer.setConnectionFactory(redisConnectionFactory);
		listenerContainer.addMessageListener(
			(message, pattern) -> receivedPayloads.offer(new String(message.getBody(), StandardCharsets.UTF_8)),
			new ChannelTopic(CryptoPriceMoveCardPublisher.CHANNEL));
		listenerContainer.afterPropertiesSet();
		listenerContainer.start();
		warmUpSubscription();
	}

	@AfterEach
	void tearDown() {
		listenerContainer.stop();
		redisTemplate.delete("price:crypto:" + CRYPTO_SYMBOL + ":snapshots");
	}

	// 구독 등록이 Redis 서버에 실제로 반영되기까지 약간의 지연이 있다 — 워밍업 메시지를 보내 실제로
	// 수신되는 것을 확인한 뒤에야 본 시나리오를 실행한다. 그렇지 않으면 구독이 늦게 걸려 본 발행을 놓칠 수 있다.
	private void warmUpSubscription() throws InterruptedException {
		redisTemplate.convertAndSend(CryptoPriceMoveCardPublisher.CHANNEL, "warmup");
		String warmup = receivedPayloads.poll(5, TimeUnit.SECONDS);
		assertThat(warmup).as("테스트 구독이 워밍업 메시지를 받지 못했다 — 이후 단정이 신뢰할 수 없다").isEqualTo("warmup");
	}

	// min-sample-count(기본 100) 세그먼트를 실제로 채우는 5분 간격 스냅샷 — ago 0~5분은 점프 이후, 그 뒤는
	// 점프 이전이다 (CryptoPriceMoveWatcherIntegrationTest와 같은 픽스처).
	private void givenEnoughCryptoSnapshotsWithARecentJump() {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(CRYPTO_SYMBOL, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(CRYPTO_SYMBOL, NOW, now, retention);
	}

	private static PriceMoveDetectionDto stockIntraday(LocalTime windowEnd) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY, windowEnd.minusMinutes(5), windowEnd,
			new BigDecimal("-0.018200"), new BigDecimal("3.2500"));
	}

	@Test
	@DisplayName("코인 카드가 확정되면 Redis 채널에 올바른 instrumentId·priceMoveEventId가 발행된다")
	void publishesCorrectInstrumentAndCardIdWhenACryptoCardIsConfirmed() throws InterruptedException {
		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, CRYPTO_SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			"https://news.example.com/card-push", NOW.minusMinutes(5), NOW));
		givenEnoughCryptoSnapshotsWithARecentJump();

		cryptoPriceMoveWatcher.watch();

		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).hasSize(1);
		PriceMoveEvent card = cards.get(0);

		String payload = receivedPayloads.poll(5, TimeUnit.SECONDS);
		assertThat(payload).as("코인 카드 확정 채널에 메시지가 발행되지 않았다").isNotNull();
		JsonNode json = objectMapper.readTree(payload);
		assertThat(json.get("market").asString()).isEqualTo("CRYPTO");
		assertThat(json.get("instrumentId").asLong()).isEqualTo(instrument.getId());
		assertThat(json.get("priceMoveEventId").asLong()).isEqualTo(card.getId());
	}

	@Test
	@DisplayName("주식 카드 확정 경로(PriceMoveCardService)를 호출해도 코인 카드 확정 채널에는 아무 메시지도 없다")
	void publishesNothingWhenAStockCardIsConfirmedThroughPriceMoveCardService() throws InterruptedException {
		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, STOCK_SYMBOL, "테스트종목", new BigDecimal("100"), 70000, true, NOW));
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 장중 기사", "테스트경제",
			"https://news.example.com/stock-card-push", LocalDateTime.of(STOCK_ORIGIN_TRADE_DATE, LocalTime.of(11, 15)),
			LocalDateTime.of(STOCK_ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		Optional<PriceMoveEvent> card = priceMoveCardService.confirmStockCard(
			instrument, STOCK_ORIGIN_TRADE_DATE, stockIntraday(LocalTime.of(11, 25)));

		assertThat(card).as("주식 카드가 실제로 확정 저장돼야 이 테스트가 의미가 있다").isPresent();
		String payload = receivedPayloads.poll(2, TimeUnit.SECONDS);
		assertThat(payload).as("주식 카드 확정은 코인 전용 채널에 아무것도 보내면 안 된다").isNull();
	}
}
