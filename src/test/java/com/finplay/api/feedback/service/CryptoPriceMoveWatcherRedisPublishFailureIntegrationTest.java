// 코인 카드 확정 push(CryptoPriceMoveCardPublisher.publish)가 예외를 던지는 상황(Redis 장애 모사)에서도
// 카드 생성 자체(price_move_events 커밋)는 성공함을 실제 MySQL로 확인한다.
// (docs/specs/028-crypto-card-sse-push/tasks.md 항목 4 — 완료 조건 "Redis 발행이 실패해도 카드 생성 자체는 성공한다")
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
import com.finplay.api.market.service.CryptoPriceMoveCardPublisher;
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

// CryptoPriceMoveCardPublisher 자체가 이미 RuntimeException을 내부에서 삼키므로(ADR-0018 §결정 3,
// CryptoPriceMoveCardPublisherTest가 단위로 이미 검증) "Redis 장애"를 있는 그대로 재현하려면 그 흡수 계층
// 아래(StringRedisTemplate)를 실패시켜야 한다. 그런데 StringRedisTemplate은 PriceStore(스냅샷 저장·조회)와
// 같은 스프링 빈을 공유해 그걸 통째로 mock하면 이 배치의 스냅샷 픽스처 자체가 무너진다. 그래서 이 테스트는 한
// 계층 위, CryptoPriceMoveCardPublisher를 통째로 mock해 그 publish() 호출 자체가 예외를 던지는 것으로
// "발행이 실패하는 상황"을 재현한다 — CryptoPriceMoveWatcher.watchOne()이 이 호출을 try-catch로 한 번 더
// 방어하고 있는지(184-192행 주석 "이중 보장")가 이 테스트가 실제로 보는 지점이다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveWatcherRedisPublishFailureIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String SYMBOL = "PUSHFAILC";

	@MockitoBean
	private NewsCollector newsCollector;

	@MockitoBean
	private CryptoPriceMoveCardPublisher cryptoPriceMoveCardPublisher;

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
	private StringRedisTemplate redisTemplate;

	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		doThrow(new RuntimeException("redis down")).when(cryptoPriceMoveCardPublisher).publish(any(), any());
	}

	@AfterEach
	void cleanUpRedisSnapshots() {
		redisTemplate.delete("price:crypto:" + SYMBOL + ":snapshots");
	}

	// CryptoPriceMoveWatcherIntegrationTest와 같은 픽스처.
	private void givenEnoughSnapshotsWithARecentJump() {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(SYMBOL, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(SYMBOL, NOW, now, retention);
	}

	@Test
	@DisplayName("카드 확정 push 호출이 예외를 던져도(Redis 장애 모사) 카드는 정상적으로 커밋된다")
	void createsCardSuccessfullyWhenPublishThrows() {
		Instrument instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			"https://news.example.com/publish-failure", NOW.minusMinutes(5), NOW));
		givenEnoughSnapshotsWithARecentJump();

		cryptoPriceMoveWatcher.watch();

		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).as("publish()가 예외를 던져도 카드 저장(price_move_events 커밋) 자체는 성공해야 한다").hasSize(1);
		assertThat(cards.get(0).getInstrument().getId()).isEqualTo(instrument.getId());
	}
}
