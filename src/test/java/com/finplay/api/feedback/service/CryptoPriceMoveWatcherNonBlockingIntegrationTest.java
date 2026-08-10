// 전송이 느린 가짜 SSE 구독자가 등록돼 있어도 CryptoPriceMoveWatcher.watch()의 소요시간이 늘어나지 않음을 확인한다
// — publish가 별도 스레드(RedisMessageListenerContainer)에서 소비되는 것의 직접 증거다.
// (docs/specs/026-crypto-card-sse-push/tasks.md 항목 4 — 비차단 검증)
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
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
import java.util.function.BooleanSupplier;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitterTestHandler;

// 코인 시드 데이터(V7, 12종)에는 스냅샷이 없어 각자 빠르게 종료한다(§C-1 nearest() 스냅샷 없음 조기 return) —
// "여러 종목 처리"는 이 12종 + 이 테스트가 추가하는 카드 확정 종목 1개로 자연스럽게 구성된다. 카드 확정 종목이
// 있어야 실제로 publish → Redis → CryptoCardPushSubscriber → 느린 emitter까지 흐름이 실제로 발동한다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveWatcherNonBlockingIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String BASELINE_SYMBOL = "NBTIMEBASE";

	private static final String SLOW_SYMBOL = "NBTIMESLOW";

	// 실제 send()가 이 시간만큼 블로킹된다 — watch() 소요시간과 비교할 임계값(1500ms)보다 충분히 커야
	// "블로킹됐다면 확실히 걸렸을" 크기다.
	private static final long SLOW_EMITTER_DELAY_MILLIS = 3000;

	// watch()가 느린 emitter의 지연을 실제로 흡수했다면 넘었을 상한 — 지연(3000ms)의 절반도 안 된다.
	private static final long MAX_ACCEPTABLE_EXTRA_MILLIS = 1500;

	@MockitoBean
	private NewsCollector newsCollector;

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	@Autowired
	private SseEmitterRegistry sseEmitterRegistry;

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

	// SseEmitterRegistry는 스프링 싱글턴이라 DB처럼 @Transactional 롤백으로 정리되지 않는다 — 이 테스트가 등록한
	// 느린 emitter를 직접 제거해 다른 테스트 클래스가 같은 컨텍스트를 공유해도(예: 동일한 @MockitoBean·@Import
	// 조합) CRYPTO 구독자 목록에 상태가 새지 않게 한다. emitter.complete()만 호출하면 내부 플래그만 세팅될 뿐
	// SseEmitterRegistry.createEmitter()가 배선한 onCompletion 콜백(=emitters.remove())은 트리거되지 않는다
	// (SseEmitterRegistryTest의 sendHeartbeatAfterEmitterCompletesRemovesItWithoutThrowing 주석과 동일한 레이스) —
	// 반드시 핸들러의 triggerCompletion()으로 그 콜백을 직접 발화시켜야 한다.
	private SseEmitterTestHandler registeredSlowHandler;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@AfterEach
	void cleanUpRedisSnapshotsAndEmitter() {
		redisTemplate.delete("price:crypto:" + BASELINE_SYMBOL + ":snapshots");
		redisTemplate.delete("price:crypto:" + SLOW_SYMBOL + ":snapshots");
		if (registeredSlowHandler != null) {
			registeredSlowHandler.triggerCompletion();
		}
	}

	// CryptoPriceMoveWatcherIntegrationTest와 같은 픽스처 — min-sample-count(기본 100) 세그먼트를 채우는
	// 5분 간격 스냅샷 + 최근 점프.
	private void givenEnoughSnapshotsWithARecentJump(String symbol) {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(symbol, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(symbol, NOW, now, retention);
	}

	private Instrument givenCryptoInstrumentWithMatchingNews(String symbol) {
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			"https://news.example.com/nonblocking/" + symbol, NOW.minusMinutes(5), NOW));
		return instrument;
	}

	private static void awaitUntil(BooleanSupplier condition, Duration timeout, String failureMessage) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(failureMessage, e);
			}
		}
		throw new AssertionError(failureMessage);
	}

	@Test
	@DisplayName("전송이 지연되는 가짜 emitter가 등록돼 있어도 watch()의 소요시간이 유의미하게 늘지 않는다")
	void watchDoesNotSlowDownWhenARegisteredSubscriberSendsSlowly() {
		// 1) 기준 — 느린 emitter 없이, 카드가 확정되는 종목 1개를 포함해 watch() 1회 소요시간을 잰다.
		givenEnoughSnapshotsWithARecentJump(BASELINE_SYMBOL);
		givenCryptoInstrumentWithMatchingNews(BASELINE_SYMBOL);

		long baselineStartNanos = System.nanoTime();
		cryptoPriceMoveWatcher.watch();
		long baselineElapsedMillis = (System.nanoTime() - baselineStartNanos) / 1_000_000;

		assertThat(priceMoveEventRepository.findAll()).hasSize(1);

		// 2) 느린 emitter를 CRYPTO 구독자 집합에 등록 — 이후 이 emitter로의 send()는 3초 블로킹된다.
		SseEmitter slowEmitter = sseEmitterRegistry.createEmitter(Market.CRYPTO);
		SseEmitterTestHandler slowHandler = new SseEmitterTestHandler();
		registeredSlowHandler = slowHandler;
		try {
			slowHandler.attachTo(slowEmitter);
		} catch (Exception e) {
			throw new AssertionError("느린 가짜 emitter 초기화 실패", e);
		}
		sseEmitterRegistry.activate(Market.CRYPTO, slowEmitter);
		// 활성화 시점까지 이미 전송된 것(retry 힌트, delaySendsBy 적용 전이라 즉시 전송됨)을 기준선으로 잡는다 —
		// 이후 "새로 늘었다"만 카드 확정 push 도착의 증거로 인정한다.
		int sentBeforeCardPush = slowHandler.getSentEvents().size();
		slowHandler.delaySendsBy(SLOW_EMITTER_DELAY_MILLIS);

		// 3) 새 종목으로 다시 카드를 확정시켜 watch()의 소요시간을 잰다 — cooldown을 피하려고 다른 심볼을 쓴다.
		givenEnoughSnapshotsWithARecentJump(SLOW_SYMBOL);
		givenCryptoInstrumentWithMatchingNews(SLOW_SYMBOL);

		long slowStartNanos = System.nanoTime();
		cryptoPriceMoveWatcher.watch();
		long slowElapsedMillis = (System.nanoTime() - slowStartNanos) / 1_000_000;

		assertThat(priceMoveEventRepository.findAll()).hasSize(2);

		// 4) 느린 emitter가 실제로 지연을 겪고 나서 카드 확정 이벤트를 받았다는 것(=경로가 실제로 발동했다는 것)을
		// 사후에 확인한다 — 지연이 리스너 스레드에서 소비되고 있을 뿐, watch() 호출 스레드를 막지 않았어야 한다.
		awaitUntil(() -> slowHandler.getSentEvents().size() > sentBeforeCardPush,
			Duration.ofMillis(SLOW_EMITTER_DELAY_MILLIS + 5000),
			"느린 emitter가 priceMoveCardConfirmed 이벤트를 결국 받지 못했다 — 경로 자체가 발동하지 않았다면 이 테스트는 의미가 없다");

		// 5) 핵심 단정 — watch() 소요시간이 느린 emitter의 지연(3000ms)만큼 늘지 않았다.
		long extraMillis = slowElapsedMillis - baselineElapsedMillis;
		assertThat(extraMillis)
			.as("watch() 소요시간(느린 emitter 등록 후=%dms, 기준=%dms)이 지연(%dms)을 흡수했다면 publish가 감시 스레드를 블로킹한 것이다",
				slowElapsedMillis, baselineElapsedMillis, SLOW_EMITTER_DELAY_MILLIS)
			.isLessThan(MAX_ACCEPTABLE_EXTRA_MILLIS);
	}
}
