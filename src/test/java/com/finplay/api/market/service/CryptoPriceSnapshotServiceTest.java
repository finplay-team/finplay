// mock(InstrumentService·PriceStore)으로 CryptoPriceSnapshotService의 스냅샷 기록 스킵·위임을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.market.config.MarketCryptoProperties;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.store.CryptoPriceDto;
import com.finplay.api.market.store.PriceSnapshotDto;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CryptoPriceSnapshotServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 12, 0, 0);
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceStore priceStore = mock(PriceStore.class);
	private final MarketCryptoProperties marketCryptoProperties = new MarketCryptoProperties("0 * * * * *", 24);

	private final CryptoPriceSnapshotService service = new CryptoPriceSnapshotService(instrumentService, priceStore,
		marketCryptoProperties, FIXED_CLOCK);

	private Instrument crypto(String symbol) {
		return Instrument.create(Market.CRYPTO, symbol, symbol, BigDecimal.ONE, 1000L, true, NOW);
	}

	// 함정 검증 — isPriceAvailable=false면 그 심볼은 건너뛴다(tasks.md 항목 1). 동결된 최신 틱이 쌓이면
	// σ가 0에 수렴했다가 복구 첫 틱에서 허위 카드가 무더기로 생성되므로, 기록 자체를 호출하지 않는지 확인한다.
	@Test
	void recordSnapshotsSkipsSymbolWhenPriceIsNotAvailable() {
		when(instrumentService.getInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(crypto("BTC")));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(false);

		service.recordSnapshots();

		verify(priceStore, never()).recordSnapshot(any(), any(), any(), any());
		verify(priceStore, never()).getLatestPrice(any());
	}

	@Test
	void recordSnapshotsRecordsLatestPriceWithRetentionDerivedFromSigmaLookbackHoursWhenAvailable() {
		when(instrumentService.getInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(crypto("BTC")));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("50000000"), NOW.minusSeconds(1))));

		service.recordSnapshots();

		verify(priceStore).recordSnapshot("BTC", NOW, new BigDecimal("50000000"), Duration.ofHours(24));
	}

	// 여러 종목 중 일부만 가용하면 가용한 종목만 기록되고 불가용 종목은 조회조차 하지 않는다.
	@Test
	void recordSnapshotsHandlesMixedAvailabilityAcrossMultipleSymbolsIndependently() {
		when(instrumentService.getInstrumentEntities(Market.CRYPTO))
			.thenReturn(List.of(crypto("BTC"), crypto("ETH")));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
		when(priceStore.isPriceAvailable("ETH")).thenReturn(false);
		when(priceStore.getLatestPrice("BTC"))
			.thenReturn(Optional.of(new CryptoPriceDto("BTC", new BigDecimal("100"), NOW)));

		service.recordSnapshots();

		verify(priceStore).recordSnapshot("BTC", NOW, new BigDecimal("100"), Duration.ofHours(24));
		verify(priceStore, never()).getLatestPrice("ETH");
		verify(priceStore, never()).recordSnapshot(eq("ETH"), any(), any(), any());
	}

	// isPriceAvailable=true인데 그 사이 최신 틱이 사라진(드문) 경우에도 예외 없이 그냥 건너뛴다.
	@Test
	void recordSnapshotsSkipsSymbolWhenLatestPriceIsEmptyDespiteAvailable() {
		when(instrumentService.getInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(crypto("BTC")));
		when(priceStore.isPriceAvailable("BTC")).thenReturn(true);
		when(priceStore.getLatestPrice("BTC")).thenReturn(Optional.empty());

		service.recordSnapshots();

		verify(priceStore, never()).recordSnapshot(any(), any(), any(), any());
	}

	@Test
	void getSnapshotsDelegatesToPriceStoreWithSameArguments() {
		LocalDateTime from = NOW.minusHours(1);
		LocalDateTime to = NOW;
		List<PriceSnapshotDto> expected = List.of(new PriceSnapshotDto(NOW.minusMinutes(5), new BigDecimal("100")));
		when(priceStore.getSnapshots("BTC", from, to)).thenReturn(expected);

		List<PriceSnapshotDto> result = service.getSnapshots("BTC", from, to);

		assertThat(result).isEqualTo(expected);
		verify(priceStore).getSnapshots("BTC", from, to);
	}
}
