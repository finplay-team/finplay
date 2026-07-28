// 재생세션(StockReplaySession)과 Clock을 조합해 주식 시장 개장 상태·현재가를 계산하는 읽기 전용 서비스
package com.finplay.api.market.service;

import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockReplayService {

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime FIRST_CANDLE_END_TIME = LocalTime.of(9, 1);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";

	// MVP 수준의 2026년 한국 공휴일 목록을 리소스 파일에서 읽어온다 (plan.md: "공휴일 판정은 리소스 파일의 공휴일 목록으로 단순 관리한다").
	// 정확한 음력 날짜(설날·추석 등) 검증과 연도별 파일 전환은 후속 결정 (plan.md Decision Gate, 외부 캘린더 API 미사용).
	private static final Set<LocalDate> HOLIDAYS_2026 = loadHolidays(HOLIDAYS_RESOURCE_PATH);

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final StockCandleRepository stockCandleRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		LocalDateTime now = LocalDateTime.now(clock);
		boolean sessionReady = findReadySession(now.toLocalDate()).isPresent();
		return computeMarketStatus(sessionReady, now);
	}

	@Transactional(readOnly = true)
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<StockReplaySession> readySession = findReadySession(now.toLocalDate());
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (readySession.isEmpty()) {
			return new StockReplayPriceDto(false, marketStatus, null, null, null);
		}

		LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
		Optional<StockCandle> revealedCandle = findRevealedCandle(instrumentId, sourceTradingDate, now.toLocalTime());
		if (revealedCandle.isEmpty()) {
			return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, null, null);
		}

		StockCandle candle = revealedCandle.get();
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(now.toLocalTime());
		var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
		LocalDateTime sourceTime = LocalDateTime.of(sourceTradingDate, candle.getCandleTime());
		return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, price, sourceTime);
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
		boolean isWeekend = today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY;
		boolean isHoliday = HOLIDAYS_2026.contains(today);
		boolean withinTradingHours = !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(MARKET_CLOSE_TIME);
		return (!isWeekend && !isHoliday && withinTradingHours) ? StockMarketStatus.OPEN : StockMarketStatus.CLOSED;
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
			// 09:00 이전(개장 전)이며 첫 분봉 구간도 아니므로 아직 공개된 분봉이 없다.
			return Optional.empty();
		}
		LocalTime cutoff = currentMinute.minusMinutes(1);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentId, sourceTradingDate, cutoff);
	}

	// 클래스패스 리소스 파일에서 공휴일 목록(한 줄에 yyyy-MM-dd, #으로 시작하는 줄은 주석)을 읽어 Set으로 반환한다.
	private static Set<LocalDate> loadHolidays(String resourcePath) {
		try (InputStream inputStream = StockReplayService.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("공휴일 리소스 파일을 찾을 수 없습니다: " + resourcePath);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				return reader
					.lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.map(LocalDate::parse)
					.collect(Collectors.toUnmodifiableSet());
			}
		} catch (IOException ex) {
			throw new IllegalStateException("공휴일 리소스 파일을 읽는 중 오류가 발생했습니다: " + resourcePath, ex);
		}
	}
}
