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
// 이슈 #104의 재현 시나리오를 실제로 고치는 핵심이다. @SpringBootTest 전체 컨텍스트에서는 build.gradle의 test
// 태스크가 spring.config.additional-location으로 이 프로퍼티만 false로 낮추는 전용 파일을 얹어 이 컴포넌트
// 자체가 생성되지 않게 한다(@DynamicPropertySource·DynamicPropertyRegistrar 빈은 모두 조건 평가 시점보다 늦게
// 반영돼 동작하지 않음을 확인 — PR #110 리뷰).
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "bithumb.feed.simulate", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BithumbFeedSimulator {

	// 시작 가격 범위(100,000~10,000,000)와 틱당 최대 변동폭(±0.5%) — 실제 시세와 맞을 필요 없는 로컬 데모용 값.
	private static final BigDecimal MIN_START_PRICE = BigDecimal.valueOf(100_000);
	private static final BigDecimal MAX_START_PRICE = BigDecimal.valueOf(10_000_000);
	private static final double MAX_STEP_RATIO = 0.005;
	// PriceStore의 10초 stale 기준을 여유 있게 만족시키기 위한 발행 주기.
	private static final long EMIT_INTERVAL_MS = 3000;

	private final InstrumentRepository instrumentRepository;
	private final FakeBithumbFeedClient fakeBithumbFeedClient;
	private final Clock clock;

	private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

	/**
	 * 합성 틱을 발행한다. <b>샌드박스(튜토리얼) 종목은 대상이 아니다</b> (이슈 #490).
	 *
	 * <p>예전에는 {@code findByMarketAndTradableTrueOrderByIdAsc}로 훑어 {@code SANDBOX_COIN_1}까지
	 * 포함됐다 — 그 종목도 {@code market=CRYPTO}·{@code tradable=true}(V32 시드)이기 때문이다. 결과로
	 * {@code price:crypto:SANDBOX_COIN_1}이 3초마다 무의미한 값으로 갱신되고 그 값으로 가격 이벤트가
	 * 발행됐다. PR #487 QA에서 튜토리얼 OCO 예약이 tick을 한 번도 부르지 않았는데 2~3초 만에 익절
	 * 체결되는 것이 3회 재현된 원인이 이것이다.
	 *
	 * <p><b>소비 측 방어는 그대로 둔다.</b> 생산 측을 막았다고 소비 측 불변식을 풀면, 다른 경로로 오염된
	 * 값이 들어올 때 다시 열린다(이슈 §완료 조건 4번).
	 *
	 * <p>{@code InstrumentService.getRealInstrumentEntities}를 재사용하지 않은 이유는 그쪽이 tradable을
	 * 보지 않기 때문이다 — 그대로 쓰면 거래 불가 종목에까지 틱이 나가 회귀가 된다.
	 */
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
