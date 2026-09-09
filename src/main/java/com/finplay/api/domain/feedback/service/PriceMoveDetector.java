package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.service.StockCandleDto;
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

@Component
@RequiredArgsConstructor
public class PriceMoveDetector {

	private static final int CHANGE_RATE_SCALE = 6;

	private static final int DETECTION_SCORE_SCALE = 4;

	private static final int GAP_CALC_SCALE = 12;

	private final FeedbackDetectionProperties properties;

	public List<PriceMoveDetectionDto> detect(
		List<StockCandleDto> candles, BigDecimal previousTradingDayClose) {
		NavigableMap<LocalTime, StockCandleDto> byTime = indexByTime(candles);
		List<PriceMoveDetectionDto> detections = new ArrayList<>();
		detectOpeningGap(byTime, previousTradingDayClose).ifPresent(detections::add);
		detections.addAll(detectIntraday(byTime));
		return detections;
	}

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
		BigDecimal score = gap.abs().divide(threshold, DETECTION_SCORE_SCALE, RoundingMode.HALF_UP);
		LocalTime firstCandleTime = firstCandle.candleTime();
		return Optional.of(new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			firstCandleTime,
			firstCandleTime,
			gap.setScale(CHANGE_RATE_SCALE, RoundingMode.HALF_UP),
			score));
	}

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

	private static boolean isPositive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	private static BigDecimal scaled(double value, int scale) {
		return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
	}

	private record Candidate(LocalTime time, double cumulativeLogReturn, double score) {
	}
}
