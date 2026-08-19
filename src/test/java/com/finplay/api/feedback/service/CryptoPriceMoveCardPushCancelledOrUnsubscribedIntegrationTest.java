// 카드 생성이 취소되면(근거 매칭 0건) 카드가 저장되지 않고, 반대로 구독자가 0명이어도 카드 생성 자체는
// 정상 성공함을 확인한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.sse.SseEmitterRegistry;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// LLM은 부르지 않는다(CryptoPriceMoveWatcherIntegrationTest와 같은 전제).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveCardPushCancelledOrUnsubscribedIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String CANCELLED_SYMBOL = "PUSHCANCEL";

	private static final String UNSUBSCRIBED_SYMBOL = "PUSHNOSUB";

	@MockitoBean
	private NewsCollector newsCollector;

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private SseEmitterRegistry sseEmitterRegistry;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("price:crypto:" + CANCELLED_SYMBOL + ":snapshots");
		redisTemplate.delete("price:crypto:" + UNSUBSCRIBED_SYMBOL + ":snapshots");
	}

	// CryptoPriceMoveWatcherIntegrationTest와 같은 픽스처.
	private void givenEnoughSnapshotsWithARecentJump(String symbol) {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(symbol, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(symbol, NOW, now, retention);
	}

	@Test
	@DisplayName("근거 매칭이 0건이면 카드 생성이 취소된다")
	void cancelsCardCreationWhenNoEvidenceIsMatched() {
		instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, CANCELLED_SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		givenEnoughSnapshotsWithARecentJump(CANCELLED_SYMBOL);
		// 근거 기사를 저장하지 않는다 — NewsCollector mock은 기본적으로 빈 목록을 반환해 온디맨드 수집도 실패한다.

		cryptoPriceMoveWatcher.watch();

		assertThat(priceMoveEventRepository.findAll()).as("카드가 생성되지 않아야 이 테스트가 의미가 있다").isEmpty();
	}

	@Test
	@DisplayName("구독자가 0명이어도(SseEmitterRegistry에 등록된 emitter 없음) 카드는 정상 생성된다")
	void createsCardSuccessfullyWhenNoSubscribersAreRegistered() {
		assertThat(sseEmitterRegistry.getEmitters(Market.CRYPTO))
			.as("이 테스트는 구독자가 없는 상태를 전제로 한다").isEmpty();

		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, UNSUBSCRIBED_SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			"https://news.example.com/no-subscribers", NOW.minusMinutes(5), NOW));
		givenEnoughSnapshotsWithARecentJump(UNSUBSCRIBED_SYMBOL);

		cryptoPriceMoveWatcher.watch();

		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).hasSize(1);
		assertThat(cards.get(0).getInstrument().getId()).isEqualTo(instrument.getId());
	}
}
