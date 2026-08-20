// seed·version·종목·run·분 인덱스만으로 29+1 OHLC와 canonical 가격을 재현하는 순수 생성기
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TutorialPriceGenerator {

	public static final short VERSION_1 = 1;
	// 대본 파일의 version과 같은 값이다 — 대본이 곧 이 버전의 입력이다(041 plan §생성기 버전 2).
	public static final short VERSION_2 = 2;
	private static final int HISTORY_CANDLE_COUNT = 29;
	private static final int PRICE_SCALE = 8;
	private static final long CURRENT_PRICE_PERIOD_MINUTES = 240L;
	private static final long UNIT_DENOMINATOR = 1_000_000L;
	private static final long CURRENT_COORDINATE_OFFSET = 1_000_000L;
	private static final long MIX_INCREMENT = 0x9E3779B97F4A7C15L;
	private static final BigDecimal STOCK_BASE_PRICE = new BigDecimal("50000.00000000");
	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");
	private static final BigDecimal MIN_FACTOR = new BigDecimal("0.90");
	private static final BigDecimal FACTOR_RANGE = new BigDecimal("0.20");
	private static final BigDecimal MAX_WICK_RATE = new BigDecimal("0.03");

	public TutorialPriceSeriesDto generate(TutorialPriceGenerationInput input, long publishedMinute) {
		validate(input, publishedMinute);
		requireVersion(input, VERSION_1);
		long mixedSeed = mixSeed(input);
		BigDecimal basePrice = basePrice(input.market());
		List<TutorialPriceCandleDto> candles = new ArrayList<>(HISTORY_CANDLE_COUNT + 1);
		candles.addAll(generateHistory(input));

		BigDecimal open = currentMinutePrice(basePrice, mixedSeed, 0L);
		BigDecimal high = open;
		BigDecimal low = open;
		long foldEnd = Math.min(publishedMinute, CURRENT_PRICE_PERIOD_MINUTES - 1L);
		for (long minute = 1L; minute <= foldEnd; minute++) {
			BigDecimal price = currentMinutePrice(basePrice, mixedSeed, minute);
			high = high.max(price);
			low = low.min(price);
		}
		BigDecimal close = currentMinutePrice(basePrice, mixedSeed, publishedMinute);
		candles.add(new TutorialPriceCandleDto(
			input.tutorialDate(), open, high.max(close), low.min(close), close, true));
		return new TutorialPriceSeriesDto(candles, close);
	}

	public BigDecimal canonicalPrice(TutorialPriceGenerationInput input, long publishedMinute) {
		validate(input, publishedMinute);
		requireVersion(input, VERSION_1);
		return currentMinutePrice(basePrice(input.market()), mixSeed(input), publishedMinute);
	}

	// 과거 29개 완결 일봉은 대본 대상이 아니다 — 039의 배경 정보이고 사건과 무관하므로 버전 2도 같은 seed
	// 생성 방식을 그대로 쓴다(041 plan §대본 설계). 버전 2는 진행 중 1봉만 대본에서 만들어 붙인다.
	//
	// **package-private이다.** 이 경로는 시장별 기준가 상수를 쓰므로, 대본 실행이 실수로 이쪽을 부르면
	// 과거봉만 1만원대에 머무른 차트가 나오면서도 기능은 정상 동작해 조용히 넘어간다. 버전 검사로 막지 않는
	// 이유는 회귀 오라클(TutorialPriceGeneratorTest)이 버전 2 입력을 일부러 이 경로에 넣어 두 갈래가 같은
	// 값을 내는지 대조하기 때문이다 — mixSeed가 generatorVersion을 섞어 버전 1 입력으로 대체할 수 없다.
	// 다른 도메인은 대본을 받는 오버로드만 볼 수 있으면 된다.
	List<TutorialPriceCandleDto> generateHistory(TutorialPriceGenerationInput input) {
		validateInput(input);
		return generateHistory(input, basePrice(input.market()));
	}

	// 버전 2의 과거 봉은 **대본의 기준가**로 만든다. 진행 중 봉만 대본 기준가를 쓰면 기준가 10만원짜리
	// 대본에서 과거 봉만 1만원대에 머물러 차트가 두 동강 난다(049 plan §1의 경고).
	public List<TutorialPriceCandleDto> generateHistory(
		TutorialPriceGenerationInput input, TutorialScenarioScript script) {
		validateInput(input);
		requireVersion(input, VERSION_2);
		requireScriptMatches(script, input);
		return generateHistory(input, script.basePrice());
	}

	private List<TutorialPriceCandleDto> generateHistory(
		TutorialPriceGenerationInput input, BigDecimal basePrice) {
		long mixedSeed = mixSeed(input);
		List<TutorialPriceCandleDto> candles = new ArrayList<>(HISTORY_CANDLE_COUNT);
		for (int index = 0; index < HISTORY_CANDLE_COUNT; index++) {
			candles.add(generateHistoryCandle(input, mixedSeed, index, basePrice));
		}
		return candles;
	}

	// 생성기 버전 2는 벽시계가 아니라 대본 위치에서 가격이 나온다 — 가상 분에 해당하는 시각이 존재하지 않으므로
	// 버전 1의 publishedMinute 진입점을 쓸 수 없다(041 plan §`order` 인터페이스 변경).
	public BigDecimal canonicalPrice(
		TutorialPriceGenerationInput input, TutorialScenarioScript script, TutorialScenarioCursor cursor) {
		validateInput(input);
		requireVersion(input, VERSION_2);
		if (cursor == null) {
			throw new IllegalArgumentException("튜토리얼 대본 또는 대본 위치가 비어 있습니다.");
		}
		requireScriptMatches(script, input);
		return TutorialScenarioPriceGenerator.canonicalPrice(script, cursor, script.basePrice());
	}

	private void requireScriptMatches(TutorialScenarioScript script, TutorialPriceGenerationInput input) {
		if (script == null) {
			throw new IllegalArgumentException("튜토리얼 대본 또는 대본 위치가 비어 있습니다.");
		}
		if (script.market() != input.market()) {
			throw new IllegalArgumentException("attempt의 시장과 다른 대본입니다.");
		}
	}

	private TutorialPriceCandleDto generateHistoryCandle(
		TutorialPriceGenerationInput input, long mixedSeed, int index, BigDecimal basePrice) {
		long coordinate = index * 4L;
		BigDecimal open = price(basePrice, mixedSeed, coordinate);
		BigDecimal close = price(basePrice, mixedSeed, coordinate + 1L);
		BigDecimal highRate = unit(mixedSeed, coordinate + 2L).multiply(MAX_WICK_RATE);
		BigDecimal lowRate = unit(mixedSeed, coordinate + 3L).multiply(MAX_WICK_RATE);
		BigDecimal high = open.max(close).multiply(BigDecimal.ONE.add(highRate))
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		BigDecimal low = open.min(close).multiply(BigDecimal.ONE.subtract(lowRate))
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		return new TutorialPriceCandleDto(
			input.tutorialDate().minusDays(HISTORY_CANDLE_COUNT - index), open, high, low, close, false);
	}

	private BigDecimal currentMinutePrice(BigDecimal basePrice, long mixedSeed, long minute) {
		long periodicMinute = Math.floorMod(minute, CURRENT_PRICE_PERIOD_MINUTES);
		return price(basePrice, mixedSeed, CURRENT_COORDINATE_OFFSET + periodicMinute);
	}

	private BigDecimal price(BigDecimal basePrice, long mixedSeed, long coordinate) {
		BigDecimal factor = MIN_FACTOR.add(unit(mixedSeed, coordinate).multiply(FACTOR_RANGE));
		return basePrice.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
	}

	// 생성기 버전 1 전용 기준가다. 버전 2는 대본 파일의 basePrice를 쓴다(049 ORDERBASICS-003).
	private BigDecimal basePrice(Market market) {
		return market == Market.STOCK ? STOCK_BASE_PRICE : CRYPTO_BASE_PRICE;
	}

	private BigDecimal unit(long mixedSeed, long coordinate) {
		long value = Long.remainderUnsigned(mix64(mixedSeed + MIX_INCREMENT * coordinate), UNIT_DENOMINATOR + 1L);
		return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(UNIT_DENOMINATOR), 12, RoundingMode.HALF_UP);
	}

	private long mixSeed(TutorialPriceGenerationInput input) {
		long value = input.priceSeed();
		value ^= input.instrumentId() * 0xD6E8FEB86659FD93L;
		value ^= input.runNumber() * 0xA0761D6478BD642FL;
		value ^= (long)input.generatorVersion() * 0xE7037ED1A0B428DBL;
		return mix64(value);
	}

	private long mix64(long value) {
		value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
		value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
		return value ^ (value >>> 31);
	}

	private void validate(TutorialPriceGenerationInput input, long publishedMinute) {
		validateInput(input);
		if (publishedMinute < 0) {
			throw new IllegalArgumentException("튜토리얼 가격 생성 입력이 올바르지 않습니다.");
		}
	}

	private void validateInput(TutorialPriceGenerationInput input) {
		if (input.generatorVersion() != VERSION_1 && input.generatorVersion() != VERSION_2) {
			throw new IllegalArgumentException("지원하지 않는 튜토리얼 가격 생성기 버전입니다.");
		}
		if (input.instrumentId() == null || input.instrumentId() <= 0 || input.runNumber() <= 0
			|| input.market() == null || input.tutorialDate() == null) {
			throw new IllegalArgumentException("튜토리얼 가격 생성 입력이 올바르지 않습니다.");
		}
	}

	private void requireVersion(TutorialPriceGenerationInput input, short expected) {
		if (input.generatorVersion() != expected) {
			throw new IllegalArgumentException("생성기 버전 " + expected + " 전용 경로입니다.");
		}
	}
}
