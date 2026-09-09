package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.config.MarketCryptoProperties;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.store.PriceSnapshotDto;
import com.finplay.api.domain.market.store.PriceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CryptoPriceSnapshotService {

	private final InstrumentService instrumentService;
	private final PriceStore priceStore;
	private final MarketCryptoProperties marketCryptoProperties;
	private final Clock clock;

	@Scheduled(cron = "${market.crypto.price-snapshot-cron}", zone = "Asia/Seoul")
	public void recordSnapshots() {
		LocalDateTime now = LocalDateTime.now(clock);
		Duration retention = Duration.ofHours(marketCryptoProperties.sigmaLookbackHours());
		List<Instrument> cryptoInstruments = instrumentService.getInstrumentEntities(Market.CRYPTO);
		for (Instrument instrument : cryptoInstruments) {
			String symbol = instrument.getSymbol();
			if (!priceStore.isPriceAvailable(symbol)) {
				log.debug("코인 가격 스냅샷 기록 건너뜀 (가격 미가용): symbol={}", symbol);
				continue;
			}
			priceStore.getLatestPrice(symbol)
				.ifPresent(latest -> priceStore.recordSnapshot(symbol, now, latest.price(), retention));
		}
	}

	public List<PriceSnapshotDto> getSnapshots(String symbol, LocalDateTime from, LocalDateTime to) {
		return priceStore.getSnapshots(symbol, from, to);
	}
}
