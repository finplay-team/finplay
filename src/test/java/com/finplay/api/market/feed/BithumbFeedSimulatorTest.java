// 목 InstrumentRepository·FakeBithumbFeedClient로 BithumbFeedSimulator의 틱 발행·랜덤워크를 검증하는 단위 테스트
package com.finplay.api.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BithumbFeedSimulatorTest {

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 30, 10, 0, 0);

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private FakeBithumbFeedClient fakeBithumbFeedClient;

	private BithumbFeedSimulator simulator;
	private Instrument btc;
	private Instrument eth;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		simulator = new BithumbFeedSimulator(instrumentRepository, fakeBithumbFeedClient, clock);

		btc = Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, FIXED_NOW);
		eth = Instrument.create(Market.CRYPTO, "ETH", "이더리움", BigDecimal.ONE, 1000, true, FIXED_NOW);
	}

	// 이슈 #490 — 샌드박스 종목이 대상에서 빠지는 것을 조회 선택으로 고정한다. 실제 필터링은 쿼리가
	// 하므로(InstrumentRepositoryTest가 검증) 여기서는 "샌드박스를 거르는 조회를 쓰는가"만 본다 —
	// 예전 조회로 되돌리면 이 테스트가 잡는다.
	@Test
	@DisplayName("샌드박스를 거르지 않는 옛 조회는 쓰지 않는다")
	void emitTicksUsesSandboxExcludingQueryOnly() {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(btc));

		simulator.emitTicks();

		verify(instrumentRepository).findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO);
		verify(instrumentRepository, never()).findByMarketAndTradableTrueOrderByIdAsc(any(Market.class));
		verify(fakeBithumbFeedClient, times(1)).emitTick(eq("BTC"), any(BigDecimal.class), eq(FIXED_NOW));
	}

	@Test
	@DisplayName("시딩된 코인 종목마다 emitTick이 정확히 1회씩 호출된다")
	void emitTicksCallsEmitTickOnceForEachSeededCryptoInstrument() {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(btc, eth));

		simulator.emitTicks();

		verify(fakeBithumbFeedClient, times(1)).emitTick(eq("BTC"), any(BigDecimal.class), eq(FIXED_NOW));
		verify(fakeBithumbFeedClient, times(1)).emitTick(eq("ETH"), any(BigDecimal.class), eq(FIXED_NOW));
	}

	@Test
	@DisplayName("종목이 없으면 emitTick을 호출하지 않는다")
	void emitTicksDoesNothingWhenNoCryptoInstrumentsSeeded() {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of());

		simulator.emitTicks();

		verify(fakeBithumbFeedClient, times(0)).emitTick(any(), any(), any());
	}

	@Test
	@DisplayName("같은 종목의 두 번째 틱은 첫 번째 가격에서 ±0.5% 범위 안에서만 변한다(랜덤워크가 이전 값을 기억)")
	void secondTickWalksFromPreviousPriceWithinHalfPercent() {
		when(instrumentRepository.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO))
			.thenReturn(List.of(btc));

		simulator.emitTicks();
		simulator.emitTicks();

		ArgumentCaptor<BigDecimal> priceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
		verify(fakeBithumbFeedClient, times(2)).emitTick(eq("BTC"), priceCaptor.capture(), eq(FIXED_NOW));

		List<BigDecimal> prices = priceCaptor.getAllValues();
		BigDecimal firstPrice = prices.get(0);
		BigDecimal secondPrice = prices.get(1);

		BigDecimal maxDelta = firstPrice.multiply(BigDecimal.valueOf(0.005));
		BigDecimal actualDelta = secondPrice.subtract(firstPrice).abs();

		assertThat(actualDelta)
			.isLessThanOrEqualTo(maxDelta.setScale(0, java.math.RoundingMode.UP).add(BigDecimal.ONE));
	}
}
