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
		List<TutorialPriceCandleDto> candles = new ArrayList<>(HISTORY_CANDLE_COUNT + 1);
		for (int index = 0; index < HISTORY_CANDLE_COUNT; index++) {
			candles.add(generateHistoryCandle(input, mixedSeed, index));
		}

		BigDecimal open = currentMinutePrice(input.market(), mixedSeed, 0L);
		BigDecimal high = open;
		BigDecimal low = open;
		long foldEnd = Math.min(publishedMinute, CURRENT_PRICE_PERIOD_MINUTES - 1L);
		for (long minute = 1L; minute <= foldEnd; minute++) {
			BigDecimal price = currentMinutePrice(input.market(), mixedSeed, minute);
			high = high.max(price);
			low = low.min(price);
		}
		BigDecimal close = currentMinutePrice(input.market(), mixedSeed, publishedMinute);
		candles.add(new TutorialPriceCandleDto(
			input.tutorialDate(), open, high.max(close), low.min(close), close, true));
		return new TutorialPriceSeriesDto(candles, close);
	}

	public BigDecimal canonicalPrice(TutorialPriceGenerationInput input, long publishedMinute) {
		validate(input, publishedMinute);
		requireVersion(input, VERSION_1);
		return currentMinutePrice(input.market(), mixSeed(input), publishedMinute);
	}

	// 생성기 버전 2는 벽시계가 아니라 대본 위치에서 가격이 나온다 — 가상 분에 해당하는 시각이 존재하지 않으므로
	// 버전 1의 publishedMinute 진입점을 쓸 수 없다(041 plan §`order` 인터페이스 변경).
	public BigDecimal canonicalPrice(
		TutorialPriceGenerationInput input, TutorialScenarioScript script, TutorialScenarioCursor cursor) {
		validateInput(input);
		requireVersion(input, VERSION_2);
		if (script == null || cursor == null) {
			throw new IllegalArgumentException("튜토리얼 대본 또는 대본 위치가 비어 있습니다.");
		}
		if (script.market() != input.market()) {
			throw new IllegalArgumentException("attempt의 시장과 다른 대본입니다.");
		}
		return TutorialScenarioPriceGenerator.canonicalPrice(script, cursor, basePrice(input.market()));
	}

	private TutorialPriceCandleDto generateHistoryCandle(
		TutorialPriceGenerationInput input, long mixedSeed, int index) {
		long coordinate = index * 4L;
		BigDecimal open = price(input.market(), mixedSeed, coordinate);
		BigDecimal close = price(input.market(), mixedSeed, coordinate + 1L);
		BigDecimal highRate = unit(mixedSeed, coordinate + 2L).multiply(MAX_WICK_RATE);
		BigDecimal lowRate = unit(mixedSeed, coordinate + 3L).multiply(MAX_WICK_RATE);
		BigDecimal high = open.max(close).multiply(BigDecimal.ONE.add(highRate))
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		BigDecimal low = open.min(close).multiply(BigDecimal.ONE.subtract(lowRate))
			.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		return new TutorialPriceCandleDto(
			input.tutorialDate().minusDays(HISTORY_CANDLE_COUNT - index), open, high, low, close, false);
	}

	private BigDecimal currentMinutePrice(Market market, long mixedSeed, long minute) {
		long periodicMinute = Math.floorMod(minute, CURRENT_PRICE_PERIOD_MINUTES);
		return price(market, mixedSeed, CURRENT_COORDINATE_OFFSET + periodicMinute);
	}

	private BigDecimal price(Market market, long mixedSeed, long coordinate) {
		BigDecimal base = basePrice(market);
		BigDecimal factor = MIN_FACTOR.add(unit(mixedSeed, coordinate).multiply(FACTOR_RANGE));
		return base.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
	}

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
