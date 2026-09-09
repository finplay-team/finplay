package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.domain.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.service.StockCandleDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PriceMoveDetectorTest {

	private static final double SPEC_Z_SCORE_K = 2.5;

	private static final int SPEC_WINDOW_MINUTES = 5;

	private static final int SPEC_MERGE_WINDOW_MINUTES = 5;

	private static final int SPEC_MAX_INTRADAY_CARDS = 2;

	private static final BigDecimal SPEC_OPENING_GAP_THRESHOLD = new BigDecimal("0.01");

	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalTime OPEN_TIME = LocalTime.of(9, 0);

	private final PriceMoveDetector detector = new PriceMoveDetector(new FeedbackDetectionProperties(
		SPEC_Z_SCORE_K,
		SPEC_WINDOW_MINUTES,
		SPEC_MERGE_WINDOW_MINUTES,
		SPEC_MAX_INTRADAY_CARDS,
		SPEC_OPENING_GAP_THRESHOLD));

	private static final long[] CONTINUOUS_CLOSES = {
		10300, 10301, 10300, 10301, 10300, 10301, 10300, 10301,
		10360, 10420, 10480, 10540, 10600,
		10601, 10600, 10601, 10600, 10601, 10600, 10601, 10600, 10601,
		10600, 10601,
		10550, 10500, 10450, 10400, 10350,
		10351, 10350, 10351, 10350, 10351,
		10390, 10430, 10470, 10510, 10550,
		10551, 10550
	};

	@Nested
	@DisplayName("탐지 ① 고정 픽스처로 장중 상위 N건과 시가 갭을 정확히 산출한다")
	class TopCandidatesAndOpeningGap {

		@Test
		@DisplayName("시가 갭 1건 뒤에 장중 카드가 점수 내림차순으로 붙는다")
		void returnsOpeningGapFirstThenIntradayCardsInDescendingScoreOrder() {
			List<PriceMoveDetectionDto> detections = detector.detect(continuousCandles(), new BigDecimal("10000"));

			assertThat(detections)
				.extracting(
					PriceMoveDetectionDto::eventType,
					PriceMoveDetectionDto::windowStart,
					PriceMoveDetectionDto::windowEnd,
					PriceMoveDetectionDto::changeRate,
					PriceMoveDetectionDto::detectionScore)
				.containsExactly(
					tuple(
						PriceMoveEventType.OPENING_GAP,
						LocalTime.of(9, 0),
						LocalTime.of(9, 0),
						new BigDecimal("0.030000"),
						new BigDecimal("3.0000")),
					tuple(
						PriceMoveEventType.INTRADAY,
						LocalTime.of(9, 7),
						LocalTime.of(9, 12),
						new BigDecimal("0.029026"),
						new BigDecimal("4.3533")),
					tuple(
						PriceMoveEventType.INTRADAY,
						LocalTime.of(9, 23),
						LocalTime.of(9, 28),
						new BigDecimal("-0.023677"),
						new BigDecimal("3.6457")));
		}

		@Test
		@DisplayName("병합 반경 안의 인접 피크는 점수가 가장 높은 하나만 남는다")
		void keepsOnlyTheHighestPeakWithinMergeWindow() {
			List<PriceMoveDetectionDto> detections = detector.detect(continuousCandles(), null);

			assertThat(detections)
				.extracting(PriceMoveDetectionDto::windowEnd)
				.containsExactly(LocalTime.of(9, 12), LocalTime.of(9, 28))
				.doesNotContain(LocalTime.of(9, 11), LocalTime.of(9, 13), LocalTime.of(9, 27));
		}

		@Test
		@DisplayName("겹치지 않는 세 번째 후보가 max-intraday-cards 상한에서 잘린다")
		void dropsThirdCandidateBecauseOfMaxIntradayCards() {
			PriceMoveDetector withThreeCards = new PriceMoveDetector(new FeedbackDetectionProperties(
				SPEC_Z_SCORE_K,
				SPEC_WINDOW_MINUTES,
				SPEC_MERGE_WINDOW_MINUTES,
				3,
				SPEC_OPENING_GAP_THRESHOLD));

			assertThat(detector.detect(continuousCandles(), null))
				.hasSize(SPEC_MAX_INTRADAY_CARDS)
				.extracting(PriceMoveDetectionDto::windowEnd)
				.doesNotContain(LocalTime.of(9, 38));
			assertThat(withThreeCards.detect(continuousCandles(), null))
				.hasSize(3)
				.extracting(PriceMoveDetectionDto::windowEnd)
				.containsExactly(LocalTime.of(9, 12), LocalTime.of(9, 28), LocalTime.of(9, 38));
		}

		@Test
		@DisplayName("점수가 동률이면 이른 시각이 앞에 온다")
		void breaksScoreTiesByEarlierTime() {
			List<PriceMoveDetectionDto> detections = detector.detect(tieCandles(), null);

			assertThat(detections)
				.extracting(PriceMoveDetectionDto::windowEnd, PriceMoveDetectionDto::detectionScore)
				.containsExactly(
					tuple(LocalTime.of(9, 10), new BigDecimal("4.5427")),
					tuple(LocalTime.of(9, 25), new BigDecimal("4.5427")));
		}

		@Test
		@DisplayName("분봉이 역순으로 들어와도 같은 결과를 낸다")
		void producesSameResultWhenCandlesArriveOutOfOrder() {
			List<StockCandleDto> reversed = new ArrayList<>(continuousCandles());
			Collections.reverse(reversed);

			assertThat(detector.detect(reversed, new BigDecimal("10000")))
				.isEqualTo(detector.detect(continuousCandles(), new BigDecimal("10000")));
		}
	}

	@Nested
	@DisplayName("탐지 ② σ=0·분봉 부족·직전 종가 없음에서 예외 없이 빈 결과를 낸다")
	class EmptyResults {

		@Test
		@DisplayName("전 구간 동일가(σ=0)면 장중 카드만 사라지고 시가 갭은 남는다")
		void skipsIntradayButKeepsOpeningGapWhenSigmaIsZero() {
			List<StockCandleDto> flat = new ArrayList<>();
			for (int minute = 0; minute <= 30; minute++) {
				flat.add(candle(OPEN_TIME.plusMinutes(minute), 10300));
			}

			List<PriceMoveDetectionDto> detections = detector.detect(flat, new BigDecimal("10000"));

			assertThat(detections)
				.extracting(PriceMoveDetectionDto::eventType)
				.containsExactly(PriceMoveEventType.OPENING_GAP);
		}

		@Test
		@DisplayName("1분 수익률 표본이 2개 미만이면 장중 카드가 없다")
		void skipsIntradayWhenFewerThanTwoOneMinuteReturns() {
			List<StockCandleDto> sparse = List.of(
				candle(OPEN_TIME, 10000),
				candle(OPEN_TIME.plusMinutes(5), 11000),
				candle(OPEN_TIME.plusMinutes(10), 12000));

			assertThat(detector.detect(sparse, null)).isEmpty();
		}

		@Test
		@DisplayName("직전 거래일 마지막 분봉이 없으면 갭만 생략되고 장중 카드는 그대로다")
		void skipsOnlyOpeningGapWhenPreviousTradingDayCloseIsNull() {
			List<PriceMoveDetectionDto> detections = detector.detect(continuousCandles(), null);

			assertThat(detections)
				.hasSize(SPEC_MAX_INTRADAY_CARDS)
				.extracting(PriceMoveDetectionDto::eventType)
				.containsOnly(PriceMoveEventType.INTRADAY);
		}

		@Test
		@DisplayName("직전 종가가 0이면 나눗셈 없이 갭만 생략한다")
		void skipsOpeningGapWhenPreviousTradingDayCloseIsZero() {
			assertThatCode(() -> detector.detect(continuousCandles(), BigDecimal.ZERO))
				.doesNotThrowAnyException();
			assertThat(detector.detect(continuousCandles(), BigDecimal.ZERO))
				.extracting(PriceMoveDetectionDto::eventType)
				.containsOnly(PriceMoveEventType.INTRADAY);
		}

		@Test
		@DisplayName("분봉이 하나도 없으면 예외 없이 빈 목록이다")
		void returnsEmptyListForEmptyCandles() {
			assertThatCode(() -> detector.detect(List.of(), null)).doesNotThrowAnyException();
			assertThat(detector.detect(List.of(), null)).isEmpty();
			assertThat(detector.detect(List.of(), new BigDecimal("10000"))).isEmpty();
		}
	}

	@Nested
	@DisplayName("탐지 ③ 결측 구간에서 점수가 부풀지 않는다")
	class MissingCandles {

		@Test
		@DisplayName("결측 구간 뒤 첫 분봉은 시각 기준으로 계산돼 후보가 되지 않는다")
		void doesNotInflateScoreAcrossMissingCandles() {
			List<StockCandleDto> candles = missingBlockCandles();

			List<PriceMoveDetectionDto> detections = detector.detect(candles, null);

			assertThat(detections).hasSize(1);
			assertThat(detections)
				.extracting(
					PriceMoveDetectionDto::windowStart,
					PriceMoveDetectionDto::windowEnd,
					PriceMoveDetectionDto::changeRate,
					PriceMoveDetectionDto::detectionScore)
				.containsExactly(tuple(
					LocalTime.of(9, 5),
					LocalTime.of(9, 10),
					new BigDecimal("0.043956"),
					new BigDecimal("3.5463")));
			assertThat(detections)
				.extracting(PriceMoveDetectionDto::windowEnd)
				.doesNotContain(LocalTime.of(9, 15));
		}
	}

	@Nested
	@DisplayName("탐지 ④ 첫 분봉이 개장 정각이 아니어도 갭 카드가 생성된다")
	class LateFirstCandle {

		@Test
		@DisplayName("첫 분봉이 09:03이어도 그 시각으로 갭 카드가 생성된다")
		void createsOpeningGapFromTheEarliestCandleNotFromNineOClock() {
			List<StockCandleDto> candles = new ArrayList<>();
			candles.add(candle(LocalTime.of(9, 3), 10150, 10150));
			for (int minute = 4; minute <= 20; minute++) {
				candles.add(candle(LocalTime.of(9, minute), 10150));
			}

			List<PriceMoveDetectionDto> detections = detector.detect(candles, new BigDecimal("10000"));

			assertThat(detections)
				.extracting(
					PriceMoveDetectionDto::eventType,
					PriceMoveDetectionDto::windowStart,
					PriceMoveDetectionDto::windowEnd,
					PriceMoveDetectionDto::changeRate,
					PriceMoveDetectionDto::detectionScore)
				.containsExactly(tuple(
					PriceMoveEventType.OPENING_GAP,
					LocalTime.of(9, 3),
					LocalTime.of(9, 3),
					new BigDecimal("0.015000"),
					new BigDecimal("1.5000")));
		}

		@Test
		@DisplayName("갭은 첫 분봉의 종가가 아니라 시가로 잰다")
		void measuresGapAgainstTheFirstCandleOpenNotItsClose() {
			List<StockCandleDto> candles = List.of(
				candle(LocalTime.of(9, 1), 10300, 10000),
				candle(LocalTime.of(9, 2), 10000, 10000));

			assertThat(detector.detect(candles, new BigDecimal("10000")))
				.extracting(PriceMoveDetectionDto::eventType, PriceMoveDetectionDto::changeRate)
				.containsExactly(tuple(PriceMoveEventType.OPENING_GAP, new BigDecimal("0.030000")));
		}
	}

	@Nested
	@DisplayName("탐지 ⑤ 시가 갭 카드의 detectionScore가 채워진다")
	class OpeningGapScore {

		@Test
		@DisplayName("하락 갭도 detectionScore가 양수로 채워지고 changeRate만 음수다")
		void fillsPositiveScoreForNegativeGap() {
			List<StockCandleDto> candles = List.of(
				candle(LocalTime.of(9, 0), 9880, 9880),
				candle(LocalTime.of(9, 1), 9880));

			assertThat(detector.detect(candles, new BigDecimal("10000")))
				.extracting(PriceMoveDetectionDto::changeRate, PriceMoveDetectionDto::detectionScore)
				.containsExactly(tuple(new BigDecimal("-0.012000"), new BigDecimal("1.2000")));
		}

		@Test
		@DisplayName("|gap|이 임계치와 정확히 같으면 카드가 생기고 score는 1.0000이다")
		void includesGapExactlyAtThreshold() {
			assertThat(detector.detect(gapFixture(10100), new BigDecimal("10000")))
				.extracting(PriceMoveDetectionDto::changeRate, PriceMoveDetectionDto::detectionScore)
				.containsExactly(tuple(new BigDecimal("0.010000"), new BigDecimal("1.0000")));
		}

		@Test
		@DisplayName("|gap|이 임계치 미만이면 갭 카드를 만들지 않는다")
		void skipsGapBelowThreshold() {
			assertThat(detector.detect(gapFixture(10099), new BigDecimal("10000"))).isEmpty();
		}

		private List<StockCandleDto> gapFixture(long open) {
			return List.of(candle(LocalTime.of(9, 0), open, open), candle(LocalTime.of(9, 1), open));
		}
	}

	private static List<StockCandleDto> continuousCandles() {
		List<StockCandleDto> candles = new ArrayList<>();
		for (int minute = 0; minute < CONTINUOUS_CLOSES.length; minute++) {
			candles.add(candle(OPEN_TIME.plusMinutes(minute), CONTINUOUS_CLOSES[minute]));
		}
		return candles;
	}

	private static List<StockCandleDto> tieCandles() {
		long[] closes = {
			10000, 10001, 10000, 10001, 10001, 10000,
			10100, 10200, 10300, 10400, 10500,
			10501, 10500, 10501, 10500, 10501, 10500, 10501, 10500, 10501, 10500,
			10605, 10710, 10815, 10920, 11025,
			11026, 11025
		};
		List<StockCandleDto> candles = new ArrayList<>();
		for (int minute = 0; minute < closes.length; minute++) {
			candles.add(candle(OPEN_TIME.plusMinutes(minute), closes[minute]));
		}
		return candles;
	}

	private static List<StockCandleDto> missingBlockCandles() {
		long[] closes = {10000, 10002, 10004, 10006, 10008, 10010, 10012, 10120, 10230, 10340, 10450};
		List<StockCandleDto> candles = new ArrayList<>();
		for (int minute = 0; minute < closes.length; minute++) {
			candles.add(candle(OPEN_TIME.plusMinutes(minute), closes[minute]));
		}
		candles.add(candle(LocalTime.of(9, 15), 10455));
		return candles;
	}

	private static StockCandleDto candle(LocalTime time, long close) {
		return candle(time, close, close);
	}

	private static StockCandleDto candle(LocalTime time, long open, long close) {
		return new StockCandleDto(
			TRADING_DATE,
			time,
			BigDecimal.valueOf(open),
			BigDecimal.valueOf(Math.max(open, close)),
			BigDecimal.valueOf(Math.min(open, close)),
			BigDecimal.valueOf(close),
			1000L);
	}
}
