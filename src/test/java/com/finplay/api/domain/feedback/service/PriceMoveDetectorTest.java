// PriceMoveDetector가 spec 012 §탐지 알고리즘(주식)·§C-2-1·§C-7대로 변동 구간을 산출하는지 검증한다.
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

// 외부 의존이 하나도 없는 순수 계산이라(§C-6) 고정 픽스처로 결과 값을 그대로 단정한다 — mock이 없다.
// 기대값의 정본은 spec.md다. 수식은 §탐지 알고리즘(주식), 임계치는 §C-7, "리터럴 시각을 쓰지 않는다"는 §C-2-1.
// 구현 파일이 아니라 spec에서 값을 가져와야 구현과 테스트가 함께 틀어지는 드리프트가 잡힌다.
//
// 기대 소수값은 spec의 의사코드를 그대로 옮긴 독립 계산(스크래치패드 시뮬레이터)으로 뽑았고, 어느 값도
// 4·6자리 반올림 경계에서 1e-5 이내로 붙어 있지 않다 — libm 구현 차이(ulp 수준)로 흔들리지 않는다.
class PriceMoveDetectorTest {

	// §C-7 feedback.detection 기본값. 여기서 값을 새로 정하지 않는다.
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

	// 09:00~09:40 연속 픽스처. 급변 구간이 셋이다.
	// - 09:08~09:12 상승 (피크 09:12, score 4.3533) — 인접 09:11·09:13도 후보라 병합 대상이다
	// - 09:24~09:28 하락 (피크 09:28, score 3.6457)
	// - 09:34~09:38 상승 (피크 09:38, score 2.8972) — 후보이지만 max-intraday-cards=2에 걸려 탈락한다
	private static final long[] CONTINUOUS_CLOSES = {
		10300, 10301, 10300, 10301, 10300, 10301, 10300, 10301, // 09:00~09:07
		10360, 10420, 10480, 10540, 10600, // 09:08~09:12
		10601, 10600, 10601, 10600, 10601, 10600, 10601, 10600, 10601, // 09:13~09:21
		10600, 10601, // 09:22~09:23
		10550, 10500, 10450, 10400, 10350, // 09:24~09:28
		10351, 10350, 10351, 10350, 10351, // 09:29~09:33
		10390, 10430, 10470, 10510, 10550, // 09:34~09:38
		10551, 10550 // 09:39~09:40
	};

	@Nested
	@DisplayName("탐지 ① 고정 픽스처로 장중 상위 N건과 시가 갭을 정확히 산출한다")
	class TopCandidatesAndOpeningGap {

		@Test
		@DisplayName("시가 갭 1건 뒤에 장중 카드가 점수 내림차순으로 붙는다")
		void returnsOpeningGapFirstThenIntradayCardsInDescendingScoreOrder() {
			List<PriceMoveDetectionDto> detections = detector.detect(continuousCandles(), new BigDecimal("10000"));

			// 시가 갭: (10300 - 10000) / 10000 = 0.03, score = 0.03 / 0.01 = 3
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

		// §탐지 알고리즘(주식) 5 — 병합 반경 안의 인접 피크는 점수가 높은 쪽만 남는다.
		// 09:11(3.5044)·09:13(3.4987)도 k=2.5를 넘는 후보이지만 09:12와 5분 이내라 버려진다.
		@Test
		@DisplayName("병합 반경 안의 인접 피크는 점수가 가장 높은 하나만 남는다")
		void keepsOnlyTheHighestPeakWithinMergeWindow() {
			List<PriceMoveDetectionDto> detections = detector.detect(continuousCandles(), null);

			assertThat(detections)
				.extracting(PriceMoveDetectionDto::windowEnd)
				.containsExactly(LocalTime.of(9, 12), LocalTime.of(9, 28))
				.doesNotContain(LocalTime.of(9, 11), LocalTime.of(9, 13), LocalTime.of(9, 27));
		}

		// §탐지 알고리즘(주식) 6 — 09:38(2.8972)은 k를 넘고 앞의 둘과 겹치지도 않는 후보다.
		// 그런데도 빠지는 이유는 상한뿐이므로, 상한을 3으로 올리면 실제로 등장한다.
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

		// 09:05→09:10과 09:20→09:25가 정확히 같은 비율(1.05)이라 두 점수가 double 수준에서 같다.
		// 동률 정렬이 흔들리면 같은 픽스처가 실행마다 다른 순서를 내므로 이른 시각이 앞이라고 단정한다.
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

		// 입력 순서·연속성을 가정하지 않는다(§탐지 알고리즘(주식) 주석) — 시각으로 다시 색인하기 때문이다.
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

		// σ=0이면 장중 카드만 없다. 시가 갭은 직전 종가와의 비교라 여전히 생긴다 — 둘을 한 덩어리로 묶어
		// "빈 결과"라고 단정하면 갭 카드가 사라져도 통과한다.
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

		// 1분 간격 쌍이 하나도 없으면 표본이 0개라 표본 표준편차(n-1)가 정의되지 않는다.
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

		// 09:00~09:10 연속(09:06→09:10에 큰 상승) → 09:11~09:14 통째 결측 → 09:15 재개.
		//
		// 시각 기준이면 t=09:15의 기준점은 close(09:10)이라 cum = ln(10455/10450) = 0.00048이고 후보가
		// 아니다. σ 표본도 1분 간격 쌍만 세므로 09:01~09:10의 10개이고 σ = 0.0054248이다.
		//
		// 행 번호 기준이면 5칸 앞이 close(09:06) = 10012라 9분치 변동(cum = 0.04330)을 5분으로 읽고,
		// r 표본에도 09:10→09:15 쌍이 1분 수익률로 섞여 σ가 0.0052806으로 내려간다. 그 결과는 아래
		// 세 단정 모두와 어긋난다 — 카드가 2건(09:15 score 3.6667 · 09:09 score 2.7639)이 되고,
		// 09:15가 09:10을 점수로 눌러 병합에서 밀어내 09:10 카드 자체가 사라진다.
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

		// 09:00~09:02가 통째로 없고 첫 분봉이 09:03이다. 09:00을 리터럴로 찾는 구현이면 그날 갭 카드가
		// 0건이 되고 예외도 로그도 남지 않는다 (§C-2-1).
		@Test
		@DisplayName("첫 분봉이 09:03이어도 그 시각으로 갭 카드가 생성된다")
		void createsOpeningGapFromTheEarliestCandleNotFromNineOClock() {
			List<StockCandleDto> candles = new ArrayList<>();
			candles.add(candle(LocalTime.of(9, 3), 10150, 10150));
			for (int minute = 4; minute <= 20; minute++) {
				candles.add(candle(LocalTime.of(9, minute), 10150));
			}

			List<PriceMoveDetectionDto> detections = detector.detect(candles, new BigDecimal("10000"));

			// (10150 - 10000) / 10000 = 0.015 >= 0.01
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

		// 첫 분봉의 시가로 재는 것이지 첫 분봉의 종가로 재는 것이 아니다 (§탐지 알고리즘(주식) open).
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

		// detection_score 컬럼이 NOT NULL이라 비면 저장 단계에서 죽거나 임의값이 들어간다.
		// 값은 |gap| / opening-gap-threshold이며 스케일은 §C-8의 DECIMAL(10,4)에 맞춘 4다.
		@Test
		@DisplayName("하락 갭도 detectionScore가 양수로 채워지고 changeRate만 음수다")
		void fillsPositiveScoreForNegativeGap() {
			List<StockCandleDto> candles = List.of(
				candle(LocalTime.of(9, 0), 9880, 9880),
				candle(LocalTime.of(9, 1), 9880));

			// gap = (9880 - 10000) / 10000 = -0.012, score = 0.012 / 0.01 = 1.2
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

	// 09:05→09:10과 09:20→09:25가 각각 정확히 1.05배라 두 구간의 로그수익률이 double 수준에서 동일하다.
	private static List<StockCandleDto> tieCandles() {
		long[] closes = {
			10000, 10001, 10000, 10001, 10001, 10000, // 09:00~09:05
			10100, 10200, 10300, 10400, 10500, // 09:06~09:10
			10501, 10500, 10501, 10500, 10501, 10500, 10501, 10500, 10501, 10500, // 09:11~09:20
			10605, 10710, 10815, 10920, 11025, // 09:21~09:25
			11026, 11025 // 09:26~09:27
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
		// 09:11~09:14는 넣지 않는다 — 거래 없는 분을 수집기가 생략한 상태다.
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
