// 로컬·데모 환경에서 실제 빗썸 연결 없이 시딩된 코인 종목에 합성 틱을 주기적으로 주입하는 시뮬레이터 (이슈 #104)
package com.finplay.api.market.feed;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
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

// !prod 프로필(FakeBithumbFeedClient와 동일 조건)에서만 켜지며, bithumb.feed.simulate.enabled가 없거나 true면
// 기본으로 활성화된다(matchIfMissing=true) — 플래그 없이 그냥 ./gradlew bootRun 해도 코인 시세가 채워지는 것이
// 이슈 #104의 재현 시나리오를 실제로 고치는 핵심이다. 자동 테스트는 src/test/resources/application.yml에서
// 이 프로퍼티를 false로 낮춰 이 컴포넌트 자체가 생성되지 않게 한다.
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "bithumb.feed.simulate", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BithumbFeedSimulator {

	// 시작 가격 범위(100,000~10,000,000)와 틱당 최대 변동폭(±0.5%) — 실제 시세와 맞을 필요 없는 로컬 데모용 값.
	private static final BigDecimal MIN_START_PRICE = BigDecimal.valueOf(100_000);
	private static final BigDecimal MAX_START_PRICE = BigDecimal.valueOf(10_000_000);
	private static final double MAX_STEP_RATIO = 0.005;
	private static final BigDecimal ONE = BigDecimal.ONE;

	private final InstrumentRepository instrumentRepository;
	private final FakeBithumbFeedClient fakeBithumbFeedClient;
	private final Clock clock;

	private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

	// PriceStore의 10초 stale 기준을 여유 있게 만족시키기 위해 3초 주기로 틱을 발행한다.
	@Scheduled(fixedRate = 3000)
	public void emitTicks() {
		List<Instrument> cryptoInstruments = instrumentRepository
			.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
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
		BigDecimal factor = ONE.add(BigDecimal.valueOf(changeRatio));
		BigDecimal next = previous.multiply(factor).setScale(0, RoundingMode.HALF_UP);
		return next.signum() > 0 ? next : previous;
	}
}
