// FakeKisRealtimePriceProvider의 정상 수신·연결 끊김 시 가격 무효화·재연결 후 새 체결이 있어야만 복귀하는 계약 단위 테스트
// (tasks.md 14번째 줄, 이슈 #82 — 실제 KIS 서버 연결은 자동 테스트 대상이 아니며 spec.md 확정대로 Fake로만 검증한다)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class FakeKisRealtimePriceProviderTest {

	private static final Long INSTRUMENT_ID = 1L;
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-29T01:00:00Z"), ZoneOffset.UTC);

	private final FakeKisRealtimePriceProvider provider = new FakeKisRealtimePriceProvider(FIXED_CLOCK);

	@Test
	void emitTickMakesCurrentPriceAvailable() {
		provider.setMarketStatus(StockMarketStatus.OPEN);
		LocalDateTime sourceTime = LocalDateTime.now(FIXED_CLOCK);

		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1000), sourceTime, 5);

		StockReplayPriceDto price = provider.getCurrentPrice(INSTRUMENT_ID);
		assertThat(price.sessionReady()).isTrue();
		assertThat(price.marketStatus()).isEqualTo(StockMarketStatus.OPEN);
		assertThat(price.isPriceAvailable()).isTrue();
		assertThat(price.price()).isEqualByComparingTo(BigDecimal.valueOf(1000));
		assertThat(price.sourceTime()).isEqualTo(sourceTime);
		assertThat(price.sourceTradingDate()).isEqualTo(sourceTime.toLocalDate());
		assertThat(provider.isConnected()).isTrue();
	}

	@Test
	void simulateDisconnectInvalidatesPriceAndMarksNotConnected() {
		LocalDateTime sourceTime = LocalDateTime.now(FIXED_CLOCK);
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1000), sourceTime, 5);

		provider.simulateDisconnect();

		assertThat(provider.isConnected()).isFalse();
		StockReplayPriceDto price = provider.getCurrentPrice(INSTRUMENT_ID);
		assertThat(price.sessionReady()).isFalse();
		assertThat(price.isPriceAvailable()).isFalse();
		assertThat(price.price()).isNull();
		assertThat(price.sourceTime()).isNull();
		assertThat(price.sourceTradingDate()).isNull();
	}

	@Test
	void emitTickWhileDisconnectedIsIgnored() {
		provider.simulateDisconnect();

		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(2000), LocalDateTime.now(FIXED_CLOCK), 1);

		StockReplayPriceDto price = provider.getCurrentPrice(INSTRUMENT_ID);
		assertThat(price.isPriceAvailable()).isFalse();
	}

	// 재연결 자체만으로는 가격이 복귀하지 않는다 — 새 체결을 받아야만 복귀한다는 MKT-004·MKT-007 원칙의 핵심 검증.
	@Test
	void simulateReconnectAloneDoesNotRestorePrice() {
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1000), LocalDateTime.now(FIXED_CLOCK), 5);
		provider.simulateDisconnect();

		provider.simulateReconnect();

		assertThat(provider.isConnected()).isTrue();
		StockReplayPriceDto price = provider.getCurrentPrice(INSTRUMENT_ID);
		assertThat(price.sessionReady()).isTrue();
		assertThat(price.isPriceAvailable()).isFalse();
		assertThat(price.price()).isNull();
	}

	@Test
	void newTickAfterReconnectRestoresPrice() {
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1000), LocalDateTime.now(FIXED_CLOCK), 5);
		provider.simulateDisconnect();
		provider.simulateReconnect();

		LocalDateTime newSourceTime = LocalDateTime.now(FIXED_CLOCK).plusMinutes(1);
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1234), newSourceTime, 2);

		StockReplayPriceDto price = provider.getCurrentPrice(INSTRUMENT_ID);
		assertThat(price.sessionReady()).isTrue();
		assertThat(price.isPriceAvailable()).isTrue();
		assertThat(price.price()).isEqualByComparingTo(BigDecimal.valueOf(1234));
		assertThat(price.sourceTime()).isEqualTo(newSourceTime);
	}

	@Test
	void getCurrentPriceForInstrumentWithoutAnyTickIsUnavailableButSessionReady() {
		StockReplayPriceDto price = provider.getCurrentPrice(999L);

		assertThat(price.sessionReady()).isTrue();
		assertThat(price.isPriceAvailable()).isFalse();
	}

	@Test
	void getCandlesDelegatesToTickAggregatorAndOnlyExposesClosedCandles() {
		LocalDateTime first = LocalDateTime.now(FIXED_CLOCK);
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1000), first, 1);
		// 다음 분으로 넘어가는 틱이 와야 첫 분봉이 마감되어 확정된다.
		provider.emitTick(INSTRUMENT_ID, BigDecimal.valueOf(1010), first.plusMinutes(1), 1);

		assertThat(provider.getCandles(INSTRUMENT_ID, null, null)).hasSize(1);
	}
}
