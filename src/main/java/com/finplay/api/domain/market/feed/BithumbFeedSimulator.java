package com.finplay.api.domain.market.feed;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "bithumb.feed.simulate", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BithumbFeedSimulator {

	private static final BigDecimal MIN_START_PRICE = BigDecimal.valueOf(100_000);
	private static final BigDecimal MAX_START_PRICE = BigDecimal.valueOf(10_000_000);
	private static final double MAX_STEP_RATIO = 0.005;
	private static final long EMIT_INTERVAL_MS = 3000;

	private final InstrumentRepository instrumentRepository;
	private final FakeBithumbFeedClient fakeBithumbFeedClient;
	private final Clock clock;

	private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

	@Scheduled(fixedRate = EMIT_INTERVAL_MS)
	public void emitTicks() {
		List<Instrument> cryptoInstruments = instrumentRepository
			.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO);
		LocalDateTime now = LocalDateTime.now(clock);
		for (Instrument instrument : cryptoInstruments) {
			String symbol = instrument.getSymbol();
			BigDecimal price = nextPrice(symbol);
			fakeBithumbFeedClient.emitTick(symbol, price, now);
		}
	}

	private BigDecimal nextPrice(String symbol) {
		return lastPrices.compute(
			symbol, (key, previous) -> previous == null ? randomStartPrice() : randomWalk(previous));
	}

	private BigDecimal randomStartPrice() {
		BigDecimal range = MAX_START_PRICE.subtract(MIN_START_PRICE);
		BigDecimal randomFraction = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble());
		return MIN_START_PRICE.add(range.multiply(randomFraction)).setScale(0, RoundingMode.HALF_UP);
	}

	private BigDecimal randomWalk(BigDecimal previous) {
		double changeRatio = ThreadLocalRandom.current().nextDouble(-MAX_STEP_RATIO, MAX_STEP_RATIO);
		BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(changeRatio));
		BigDecimal next = previous.multiply(factor).setScale(0, RoundingMode.HALF_UP);
		return next.signum() > 0 ? next : previous;
	}
}
