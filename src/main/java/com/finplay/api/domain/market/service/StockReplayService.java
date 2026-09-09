package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockReplayService {

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime FIRST_CANDLE_END_TIME = LocalTime.of(9, 1);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	private static final LocalTime END_OF_DAY = LocalTime.MAX.withNano(0);

	private static final int MAX_AGGREGATED_CANDLES = 200;
	private static final long LOOKBACK_FLOOR_DAYS = 400;
	private static final long LOOKBACK_FLOOR_WEEKS = 200;
	private static final long LOOKBACK_FLOOR_MONTHS = 200;

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final StockCandleRepository stockCandleRepository;
	private final StockDailyCandleRepository stockDailyCandleRepository;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;

	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		LocalDateTime now = LocalDateTime.now(clock);
		boolean sessionReady = findReadySession(now.toLocalDate()).isPresent();
		return computeMarketStatus(sessionReady, now);
	}

	@Transactional(readOnly = true)
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return getCurrentPrices(List.of(instrumentId)).get(0);
	}

	@Transactional(readOnly = true)
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (marketStatus == StockMarketStatus.OPEN) {
			return buildTodaySessionPrices(instrumentIds, marketStatus, readySession.orElseThrow(), now.toLocalTime());
		}
		return buildClosedMarketPrices(instrumentIds, marketStatus, readySession, today, now.toLocalTime());
	}

	private List<StockReplayPriceDto> buildTodaySessionPrices(
		List<Long> instrumentIds, StockMarketStatus marketStatus, StockReplaySession replaySession, LocalTime nowTime) {
		LocalDate sourceTradingDate = replaySession.getSourceTradingDate();
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(nowTime);
		return instrumentIds.stream().map(instrumentId -> {
			Optional<StockCandle> revealedCandle = findRevealedCandle(instrumentId, sourceTradingDate, nowTime);
			if (revealedCandle.isEmpty()) {
				return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, null, null, replaySession);
			}
			StockCandle candle = revealedCandle.get();
			var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
			LocalDateTime sourceTime = LocalDateTime.of(sourceTradingDate, candle.getCandleTime());
			return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, price, sourceTime, replaySession);
		}).toList();
	}

	private List<StockReplayPriceDto> buildClosedMarketPrices(
		List<Long> instrumentIds, StockMarketStatus marketStatus, Optional<StockReplaySession> readySession,
		LocalDate today, LocalTime nowTime) {
		StockReplaySession todaySession = readySession.orElse(null);
		LocalDate todaySourceTradingDate = readySession.map(StockReplaySession::getSourceTradingDate).orElse(null);
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(nowTime);

		List<Optional<StockCandle>> todayCandles = readySession.isEmpty()
			? instrumentIds.stream().<Optional<StockCandle>>map(id -> Optional.empty()).toList()
			: instrumentIds.stream().map(id -> findRevealedCandle(id, todaySourceTradingDate, nowTime)).toList();

		boolean anyFallbackNeeded = todayCandles.stream().anyMatch(Optional::isEmpty);
		Optional<StockReplaySession> fallbackSession = anyFallbackNeeded
			? findFallbackSession(today)
			: Optional.empty();

		List<StockReplayPriceDto> results = new ArrayList<>(instrumentIds.size());
		for (int i = 0; i < instrumentIds.size(); i++) {
			Optional<StockCandle> todayCandle = todayCandles.get(i);
			if (todayCandle.isPresent()) {
				StockCandle candle = todayCandle.get();
				var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
				LocalDateTime sourceTime = LocalDateTime.of(todaySourceTradingDate, candle.getCandleTime());
				results.add(new StockReplayPriceDto(
					true, marketStatus, todaySourceTradingDate, price, sourceTime, todaySession));
				continue;
			}
			Long instrumentId = instrumentIds.get(i);
			results.add(fallbackSession
				.flatMap(session -> buildFallbackPrice(instrumentId, marketStatus, session))
				.orElseGet(() -> new StockReplayPriceDto(
					readySession.isPresent(), marketStatus, todaySourceTradingDate, null, null, todaySession)));
		}
		return results;
	}

	private Optional<StockReplayPriceDto> buildFallbackPrice(
		Long instrumentId, StockMarketStatus marketStatus, StockReplaySession fallbackSession) {
		LocalDate fallbackTradingDate = fallbackSession.getSourceTradingDate();
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentId, fallbackTradingDate)
			.map(candle -> new StockReplayPriceDto(
				false, marketStatus, fallbackTradingDate, candle.getClose(),
				LocalDateTime.of(fallbackTradingDate, candle.getCandleTime()), null));
	}

	private Optional<StockReplaySession> findFallbackSession(LocalDate today) {
		return stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(today, PreparationStatus.READY);
	}

	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		LocalTime rangeStart = from != null ? from.toLocalTime() : LocalTime.MIN;
		LocalTime requestedEnd = to != null ? to.toLocalTime() : END_OF_DAY;

		if (readySession.isPresent()) {
			LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
			Optional<LocalTime> cutoff = resolveRevealCutoff(now.toLocalTime());
			if (cutoff.isPresent()) {
				LocalTime rangeEnd = requestedEnd.isBefore(cutoff.get()) ? requestedEnd : cutoff.get();
				if (!rangeStart.isAfter(rangeEnd)) {
					List<StockCandleDto> todayCandles = queryRevealedCandles(instrumentId, sourceTradingDate,
						rangeStart, rangeEnd);
					if (!todayCandles.isEmpty() || marketStatus != StockMarketStatus.CLOSED) {
						return todayCandles;
					}
				}
			}
		}

		if (marketStatus != StockMarketStatus.CLOSED) {
			return List.of();
		}
		return findFallbackSession(today)
			.map(
				session -> queryRevealedCandles(instrumentId, session.getSourceTradingDate(), rangeStart, requestedEnd))
			.orElse(List.of());
	}

	private List<StockCandleDto> queryRevealedCandles(
		Long instrumentId, LocalDate tradingDate, LocalTime rangeStart, LocalTime rangeEnd) {
		return stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentId, tradingDate, rangeStart, rangeEnd)
			.stream()
			.map(StockCandleDto::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedAggregatedCandles(
		Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate) {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDate today = now.toLocalDate();
		Optional<StockReplaySession> readySession = findReadySession(today);
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (readySession.isPresent()) {
			LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
			List<StockCandleDto> todayResult = buildAggregatedCandles(
				instrumentId, interval, fromDate, toDate, sourceTradingDate, resolveRevealCutoff(now.toLocalTime()));
			if (!todayResult.isEmpty() || marketStatus != StockMarketStatus.CLOSED) {
				return todayResult;
			}
		}

		if (marketStatus != StockMarketStatus.CLOSED) {
			return List.of();
		}
		return findFallbackSession(today)
			.map(session -> buildAggregatedCandles(
				instrumentId, interval, fromDate, toDate, session.getSourceTradingDate(), Optional.of(END_OF_DAY)))
			.orElse(List.of());
	}

	private List<StockCandleDto> buildAggregatedCandles(
		Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate,
		LocalDate sourceTradingDate, Optional<LocalTime> sourceTradingDateCutoff) {
		LocalDate requestedEnd = toDate != null ? toDate : sourceTradingDate;
		LocalDate rangeEnd = requestedEnd.isBefore(sourceTradingDate) ? requestedEnd : sourceTradingDate;
		LocalDate rangeStart = fromDate != null ? fromDate : lookbackFloor(interval, rangeEnd);
		if (rangeStart.isAfter(rangeEnd)) {
			return List.of();
		}

		List<StockCandleDto> minuteCandles = new ArrayList<>();

		LocalDate sourceTradingDateMinusOne = sourceTradingDate.minusDays(1);
		LocalDate pastEnd = rangeEnd.isBefore(sourceTradingDateMinusOne) ? rangeEnd : sourceTradingDateMinusOne;
		if (!rangeStart.isAfter(pastEnd)) {
			List<StockDailyCandle> archiveRows = stockDailyCandleRepository
				.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(instrumentId, rangeStart, pastEnd);
			LocalDate narrowedRangeStart = narrowRangeStart(instrumentId, interval, rangeStart, pastEnd, archiveRows);
			minuteCandles.addAll(
				pastCandlesPreferringArchive(instrumentId, narrowedRangeStart, pastEnd, archiveRows));
		}

		boolean sourceTradingDateInRange = !rangeStart.isAfter(sourceTradingDate)
			&& !sourceTradingDate.isAfter(rangeEnd);
		if (sourceTradingDateInRange && sourceTradingDateCutoff.isPresent()) {
			minuteCandles.addAll(
				stockCandleRepository
					.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
						instrumentId, sourceTradingDate, LocalTime.MIN, sourceTradingDateCutoff.get())
					.stream()
					.map(StockCandleDto::from)
					.toList());
		}

		List<StockCandleDto> aggregated = StockCandleAggregator.aggregate(minuteCandles, interval).stream()
			.filter(candle -> !candle.tradingDate().isBefore(rangeStart))
			.toList();
		if (aggregated.size() <= MAX_AGGREGATED_CANDLES) {
			return aggregated;
		}
		return aggregated.subList(aggregated.size() - MAX_AGGREGATED_CANDLES, aggregated.size());
	}

	private List<StockCandleDto> pastCandlesPreferringArchive(
		Long instrumentId, LocalDate rangeStart, LocalDate rangeEnd, List<StockDailyCandle> archiveRowsInWiderRange) {
		Set<LocalDate> archiveDates = new HashSet<>();
		List<StockCandleDto> merged = new ArrayList<>();
		for (StockDailyCandle archiveCandle : archiveRowsInWiderRange) {
			if (archiveCandle.getTradingDate().isBefore(rangeStart)) {
				continue;
			}
			archiveDates.add(archiveCandle.getTradingDate());
			merged.add(toArchiveDto(archiveCandle));
		}
		stockCandleRepository
			.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentId, rangeStart,
				rangeEnd)
			.stream()
			.filter(candle -> !archiveDates.contains(candle.getTradingDate()))
			.map(StockCandleDto::from)
			.forEach(merged::add);
		merged.sort(Comparator.comparing(StockCandleDto::tradingDate).thenComparing(StockCandleDto::candleTime));
		return merged;
	}

	private static StockCandleDto toArchiveDto(StockDailyCandle candle) {
		return new StockCandleDto(
			candle.getTradingDate(), LocalTime.MIDNIGHT,
			candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());
	}

	@Transactional(readOnly = true)
	public StockReplaySessionDto getCurrentReplaySession() {
		return findReadySession(LocalDate.now(clock))
			.map(session -> new StockReplaySessionDto(true, session.getSourceTradingDate()))
			.orElseGet(() -> new StockReplaySessionDto(false, null));
	}

	@Transactional(readOnly = true)
	public Optional<LocalDate> getSourceTradingDate(LocalDate serviceDate) {
		return findReadySession(serviceDate).map(StockReplaySession::getSourceTradingDate);
	}

	@Transactional(readOnly = true)
	public List<StockCandleDto> getFullDayCandles(Long instrumentId, LocalDate tradingDate) {
		return stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrumentId, tradingDate)
			.stream()
			.map(StockCandleDto::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public Optional<BigDecimal> getPreviousTradingDayClose(Long instrumentId, LocalDate tradingDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(tradingDate);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc(instrumentId, previousTradingDate)
			.map(StockCandle::getClose);
	}

	private LocalDate lookbackFloor(CandleInterval interval, LocalDate rangeEnd) {
		return switch (interval) {
			case ONE_DAY -> rangeEnd.minusDays(LOOKBACK_FLOOR_DAYS);
			case ONE_WEEK -> rangeEnd.minusWeeks(LOOKBACK_FLOOR_WEEKS);
			case ONE_MONTH -> rangeEnd.minusMonths(LOOKBACK_FLOOR_MONTHS);
			case ONE_MINUTE -> throw new IllegalArgumentException("집계 캔들 전용 메서드입니다: " + interval);
		};
	}

	private LocalDate narrowRangeStart(
		Long instrumentId, CandleInterval interval, LocalDate rangeStart, LocalDate queryEnd,
		List<StockDailyCandle> archiveRows) {
		int fetchLimit = MAX_AGGREGATED_CANDLES * maxTradingDaysPerBucket(interval);
		List<LocalDate> minuteTradingDates = stockCandleRepository
			.findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
				instrumentId, rangeStart, queryEnd, PageRequest.of(0, fetchLimit));
		Set<LocalDate> combinedDates = new HashSet<>(minuteTradingDates);
		archiveRows.forEach(candle -> combinedDates.add(candle.getTradingDate()));
		List<LocalDate> recentTradingDates = combinedDates.stream()
			.sorted(Comparator.reverseOrder())
			.limit(fetchLimit)
			.toList();
		if (recentTradingDates.isEmpty()) {
			return rangeStart;
		}

		Set<LocalDate> bucketsSeen = new HashSet<>();
		LocalDate narrowedFloor = rangeStart;
		for (LocalDate tradingDate : recentTradingDates) {
			LocalDate bucketStart = StockCandleAggregator.resolveBucketStart(tradingDate, interval);
			narrowedFloor = bucketStart;
			bucketsSeen.add(bucketStart);
			if (bucketsSeen.size() >= MAX_AGGREGATED_CANDLES) {
				break;
			}
		}
		return narrowedFloor.isAfter(rangeStart) ? narrowedFloor : rangeStart;
	}

	private static int maxTradingDaysPerBucket(CandleInterval interval) {
		return switch (interval) {
			case ONE_DAY -> 1;
			case ONE_WEEK -> 7;
			case ONE_MONTH -> 31;
			case ONE_MINUTE -> throw new IllegalArgumentException("집계 캔들 전용 메서드입니다: " + interval);
		};
	}

	private Optional<StockReplaySession> findReadySession(LocalDate serviceDate) {
		return stockReplaySessionRepository
			.findByServiceDate(serviceDate)
			.filter(session -> session.getPreparationStatus() == PreparationStatus.READY);
	}

	private StockMarketStatus computeMarketStatus(boolean sessionReady, LocalDateTime now) {
		if (!sessionReady) {
			return StockMarketStatus.CLOSED;
		}
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		boolean withinTradingHours = !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(MARKET_CLOSE_TIME);
		return (businessDayCalendar.isBusinessDay(today) && withinTradingHours)
			? StockMarketStatus.OPEN
			: StockMarketStatus.CLOSED;
	}

	private boolean isWithinFirstCandleWindow(LocalTime time) {
		return !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(FIRST_CANDLE_END_TIME);
	}

	private Optional<StockCandle> findRevealedCandle(Long instrumentId, LocalDate sourceTradingDate, LocalTime now) {
		if (isWithinFirstCandleWindow(now)) {
			return stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
				instrumentId, sourceTradingDate);
		}
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			return Optional.empty();
		}
		LocalTime cutoff = currentMinute.minusMinutes(1);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentId, sourceTradingDate, cutoff);
	}

	private Optional<LocalTime> resolveRevealCutoff(LocalTime now) {
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			return Optional.empty();
		}
		return Optional.of(currentMinute.minusMinutes(1));
	}
}
