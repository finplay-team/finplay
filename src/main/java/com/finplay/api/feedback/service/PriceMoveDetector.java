// 하루치 분봉과 직전 거래일 종가로 변동 구간을 찾아내는 순수 계산 — 주식 전용이며 외부 의존이 없다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.market.service.StockCandleDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수식의 정본은 spec §탐지 알고리즘(주식)이고 임계치는 §C-7이다. 여기서 값을 새로 정하지 않는다.
 *
 * <p><b>DB·시계·LLM을 보지 않는다</b> (§C-6). 입력은 하루치 분봉과 직전 거래일 마지막 분봉의 종가뿐이라
 * 고정 픽스처로 결과를 그대로 단정할 수 있다. 종목·서비스 날짜·근거 기사·서술은 확정 경로
 * ({@code PriceMoveCardService})가 붙인다.
 *
 * <p><b>모든 인덱싱이 행 번호가 아니라 시각 기준이다</b> (§탐지 알고리즘(주식) [결측]). 수집기가 거래 없는
 * 분을 생략할 수 있어 분봉은 연속이 아니며, 행 번호로 {@code W}칸 앞을 집으면 실제 경과 시간이 {@code W}분이
 * 아닌데도 {@code σ·√W}로 나누어 <b>점수가 조용히 부풀어 오른다.</b> 순수 함수라 예외도 나지 않는다.
 *
 * <p><b>시가·마지막 분봉을 리터럴 시각으로 찾지 않는다</b> (§C-2-1). 09:00 분봉이 없는 날이 정상이므로
 * 리터럴로 찾으면 갭 카드가 매일 영구히 0건이 되고 로그조차 남지 않는다. "첫 분봉"은 입력에서 가장 이른
 * 분봉이며, 직전 거래일 "마지막" 분봉의 종가는 호출부가 넘긴다.
 *
 * <p><b>결과가 비는 것은 오류가 아니다</b> — σ가 0이거나 표본이 2개 미만이면 장중 카드가 없고, 직전 종가가
 * 없으면 갭 카드가 없다 (§FEED-002). 예외를 던지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PriceMoveDetector {

	// changeRate·detectionScore 컬럼의 스케일이다 (§C-8 DECIMAL(10,6)·DECIMAL(10,4)). 저장 직전에 반올림되어
	// 계산값과 조회값이 갈리지 않도록 탐지 단계에서 컬럼 스케일로 맞춘다.
	private static final int CHANGE_RATE_SCALE = 6;

	private static final int DETECTION_SCORE_SCALE = 4;

	// 갭 비교용 중간 계산 스케일. 반올림 전에 임계치와 비교하려고 컬럼 스케일보다 넉넉히 둔다.
	private static final int GAP_CALC_SCALE = 12;

	private final FeedbackDetectionProperties properties;

	/**
	 * 원본 거래일 하루치 분봉에서 장중 변동 구간과 시가 갭을 찾는다.
	 *
	 * <p>반환 순서는 <b>시가 갭 → 장중(점수 내림차순)</b>이다. §C-6의 개장 전 배치 생성 순서가 시가 갭 카드를
	 * 장중 카드보다 먼저 만들도록 정해 두었으므로, 호출부가 순서를 다시 세우지 않고 그대로 순회하면 된다.
	 *
	 * @param candles 원본 거래일 하루치 분봉. 순서·연속성을 가정하지 않는다 — 시각으로 다시 색인한다
	 * @param previousTradingDayClose 직전 거래일 <b>마지막</b> 분봉의 종가. 없으면 {@code null}을 넘긴다
	 *     (갭 카드만 생략되고 오류가 아니다)
	 * @return 확정 전 변동 구간 목록. 없으면 빈 목록
	 */
	public List<PriceMoveDetectionDto> detect(
		List<StockCandleDto> candles, BigDecimal previousTradingDayClose) {
		NavigableMap<LocalTime, StockCandleDto> byTime = indexByTime(candles);
		List<PriceMoveDetectionDto> detections = new ArrayList<>();
		detectOpeningGap(byTime, previousTradingDayClose).ifPresent(detections::add);
		detections.addAll(detectIntraday(byTime));
		return detections;
	}

	// 시각으로 색인한다. 같은 시각이 두 번 오면 먼저 온 것을 남긴다 — 어느 쪽을 골라도 값이 같아야 정상이고,
	// 다르면 수집 쪽 문제라 여기서 임의로 덮어쓰면 오히려 원인을 가린다.
	private static NavigableMap<LocalTime, StockCandleDto> indexByTime(List<StockCandleDto> candles) {
		NavigableMap<LocalTime, StockCandleDto> byTime = new TreeMap<>();
		for (StockCandleDto candle : candles) {
			byTime.putIfAbsent(candle.candleTime(), candle);
		}
		return byTime;
	}

	private List<PriceMoveDetectionDto> detectIntraday(NavigableMap<LocalTime, StockCandleDto> byTime) {
		int windowMinutes = properties.windowMinutes();
		List<Double> returns = new ArrayList<>();
		for (LocalTime time : byTime.navigableKeySet()) {
			logReturn(byTime, time.minusMinutes(1), time).ifPresent(returns::add);
		}
		// 표본 2개 미만이면 표본 표준편차(n-1)의 분모가 0 이하라 정의되지 않는다 (§탐지 알고리즘(주식) 2).
		if (returns.size() < 2) {
			return List.of();
		}
		double sigma = sampleStandardDeviation(returns);
		if (sigma == 0) {
			return List.of();
		}

		double denominator = sigma * Math.sqrt(windowMinutes);
		List<Candidate> candidates = new ArrayList<>();
		for (LocalTime time : byTime.navigableKeySet()) {
			OptionalDouble cumulative = logReturn(byTime, time.minusMinutes(windowMinutes), time);
			if (cumulative.isEmpty()) {
				continue;
			}
			double score = Math.abs(cumulative.getAsDouble()) / denominator;
			if (score >= properties.zScoreK()) {
				candidates.add(new Candidate(time, cumulative.getAsDouble(), score));
			}
		}
		return adopt(candidates, windowMinutes);
	}

	// 병합과 채택 (§탐지 알고리즘(주식) 5·6). 점수 동률은 이른 시각을 앞에 둬 결과를 결정적으로 만든다 —
	// 순서가 흔들리면 같은 픽스처가 실행마다 다른 카드를 낸다.
	private List<PriceMoveDetectionDto> adopt(List<Candidate> candidates, int windowMinutes) {
		candidates.sort(
			Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::time));
		List<Candidate> adopted = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (adopted.size() >= properties.maxIntradayCards()) {
				break;
			}
			if (overlapsAdopted(adopted, candidate)) {
				continue;
			}
			adopted.add(candidate);
		}

		List<PriceMoveDetectionDto> detections = new ArrayList<>();
		for (Candidate candidate : adopted) {
			detections.add(new PriceMoveDetectionDto(
				PriceMoveEventType.INTRADAY,
				candidate.time().minusMinutes(windowMinutes),
				candidate.time(),
				// 로그수익률을 단순수익률로 되돌린다. expm1은 exp(x) - 1을 x가 작을 때도 정밀하게 계산한다.
				scaled(Math.expm1(candidate.cumulativeLogReturn()), CHANGE_RATE_SCALE),
				scaled(candidate.score(), DETECTION_SCORE_SCALE)));
		}
		return detections;
	}

	private boolean overlapsAdopted(List<Candidate> adopted, Candidate candidate) {
		for (Candidate peak : adopted) {
			long gapMinutes = Math.abs(Duration.between(peak.time(), candidate.time()).toMinutes());
			if (gapMinutes <= properties.mergeWindowMinutes()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 시가 갭 1건 (§탐지 알고리즘(주식)). 직전 거래일 마지막 분봉이 없거나 분봉이 하나도 없으면 만들지 않으며
	 * <b>오류가 아니다</b> (§FEED-002).
	 */
	private Optional<PriceMoveDetectionDto> detectOpeningGap(
		NavigableMap<LocalTime, StockCandleDto> byTime, BigDecimal previousTradingDayClose) {
		if (byTime.isEmpty() || !isPositive(previousTradingDayClose)) {
			return Optional.empty();
		}
		StockCandleDto firstCandle = byTime.firstEntry().getValue();
		BigDecimal open = firstCandle.open();
		if (!isPositive(open)) {
			return Optional.empty();
		}

		BigDecimal threshold = properties.openingGapThreshold();
		BigDecimal gap = open.subtract(previousTradingDayClose)
			.divide(previousTradingDayClose, GAP_CALC_SCALE, RoundingMode.HALF_UP);
		if (gap.abs().compareTo(threshold) < 0) {
			return Optional.empty();
		}
		// 갭 카드도 detectionScore를 반드시 채운다 — 컬럼이 NOT NULL이라 비워 두면 임의값이 들어간다.
		BigDecimal score = gap.abs().divide(threshold, DETECTION_SCORE_SCALE, RoundingMode.HALF_UP);
		LocalTime firstCandleTime = firstCandle.candleTime();
		return Optional.of(new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			firstCandleTime,
			firstCandleTime,
			gap.setScale(CHANGE_RATE_SCALE, RoundingMode.HALF_UP),
			score));
	}

	// 두 시각의 종가로 로그수익률을 만든다. 한쪽 분봉이 없으면 그 표본·후보를 통째로 제외한다 — 결측 구간을
	// 앞뒤 분봉으로 메우면 경과 시간이 달라져 점수가 부푼다([결측]).
	private static OptionalDouble logReturn(
		NavigableMap<LocalTime, StockCandleDto> byTime, LocalTime from, LocalTime to) {
		StockCandleDto fromCandle = byTime.get(from);
		StockCandleDto toCandle = byTime.get(to);
		if (fromCandle == null || toCandle == null) {
			return OptionalDouble.empty();
		}
		if (!isPositive(fromCandle.close()) || !isPositive(toCandle.close())) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(
			Math.log(toCandle.close().doubleValue() / fromCandle.close().doubleValue()));
	}

	// 표본 표준편차 (n-1). 모표준편차(n)로 나누면 σ가 작아져 점수가 전부 커지고 카드가 상한까지 찬다.
	private static double sampleStandardDeviation(List<Double> returns) {
		double mean = 0;
		for (double value : returns) {
			mean += value;
		}
		mean /= returns.size();

		double squaredSum = 0;
		for (double value : returns) {
			squaredSum += (value - mean) * (value - mean);
		}
		return Math.sqrt(squaredSum / (returns.size() - 1));
	}

	// 가격이 0 이하이면 로그·나눗셈이 성립하지 않는다. 실데이터에는 없는 값이지만 여기서 걸러야
	// NaN이 후보 판정을 조용히 통과하거나 나눗셈이 배치를 죽이는 일이 없다.
	private static boolean isPositive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	private static BigDecimal scaled(double value, int scale) {
		return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
	}

	// 채택 전 후보. 점수와 누적 로그수익률을 함께 들고 다녀야 병합 뒤에 changeRate를 다시 계산하지 않는다.
	private record Candidate(LocalTime time, double cumulativeLogReturn, double score) {
	}
}
