// 코인 가격 스냅샷을 매 분 기록하고, feedback이 구간 조회할 수 있게 하는 서비스 (spec 012 §코인 가격 스냅샷).
package com.finplay.api.market.service;

import com.finplay.api.market.config.MarketCryptoProperties;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.store.PriceSnapshotDto;
import com.finplay.api.market.store.PriceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// PriceStore는 @Component이고 심볼 목록을 모른다(§C-6) — 이 서비스가 심볼·시각만 넘기고 매 분 기록 스케줄을
// 소유한다. feedback은 이 서비스만 알고 PriceStore를 직접 주입하지 않는다.
@Service
@Slf4j
@RequiredArgsConstructor
public class CryptoPriceSnapshotService {

	private final InstrumentService instrumentService;
	private final PriceStore priceStore;
	private final MarketCryptoProperties marketCryptoProperties;
	private final Clock clock;

	/**
	 * 코인 전 종목의 현재가를 매 분 스냅샷으로 적재한다. 크론은 {@code market.crypto.price-snapshot-cron}이다.
	 *
	 * <p>기존 {@code market}의 두 크론(KisHistoricalCandleCollector·StockReplaySessionScheduler)은 리터럴을
	 * 직접 박아 두지만, 이 크론은 §C-1 표에 named 프로퍼티로 명시돼 있어 참조로 단다.
	 *
	 * <p><b>{@code zone}을 반드시 붙인다</b>(§C-1). 배포 JVM 기본 타임존이 UTC라 빠뜨리면 예외도 로그도 없이
	 * 엉뚱한 시각에 돈다.
	 *
	 * <p>{@code PriceStore.isPriceAvailable(symbol)}이 {@code false}면 그 심볼은 건너뛴다 — 동결된 최신 틱이
	 * 쌓이면 σ가 0에 수렴했다가 복구 첫 틱에서 허위 카드가 무더기로 생성된다.
	 */
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

	// feedback은 이 메서드만 안다(§C-6). PriceStore는 @Component라 다른 도메인이 직접 주입하지 않는다.
	public List<PriceSnapshotDto> getSnapshots(String symbol, LocalDateTime from, LocalDateTime to) {
		return priceStore.getSnapshots(symbol, from, to);
	}
}
