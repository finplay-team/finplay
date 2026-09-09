package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockReplaySessionScheduler {

	private static final int MAX_LOOKBACK_BUSINESS_DAYS = 30;
	private static final String NO_VALIDATED_DATA_FAILURE_REASON = "검증 완료된 거래일 데이터를 찾지 못했습니다.";

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final MarketDataImportRepository marketDataImportRepository;
	private final StockCandleRepository stockCandleRepository;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;

	@Scheduled(cron = "0 40 8 * * MON-FRI", zone = "Asia/Seoul")
	@Transactional
	public void resolveTodaySession() {
		LocalDate serviceDate = LocalDate.now(clock);
		LocalDateTime resolvedAt = LocalDateTime.now(clock);

		StockReplaySession session = stockReplaySessionRepository
			.findByServiceDate(serviceDate)
			.orElseGet(() -> stockReplaySessionRepository.save(
				StockReplaySession.preparing(serviceDate, null, resolvedAt)));

		if (session.getPreparationStatus() != PreparationStatus.PREPARING) {
			log.info("서비스 날짜 {}의 재생세션이 이미 {} 상태입니다 — 재확정하지 않습니다.", serviceDate,
				session.getPreparationStatus());
			return;
		}

		Optional<LocalDate> validatedTradingDate = resolveLatestValidatedTradingDate(serviceDate);
		if (validatedTradingDate.isPresent()) {
			session.resolveReady(validatedTradingDate.get(), resolvedAt);
			log.info("재생세션이 READY로 확정되었습니다 (serviceDate={}, sourceTradingDate={})", serviceDate,
				validatedTradingDate.get());
		} else {
			session.resolveFailed(null, resolvedAt, NO_VALIDATED_DATA_FAILURE_REASON);
			log.warn("검증 완료된 거래일을 찾지 못해 재생세션이 FAILED로 확정되었습니다 (serviceDate={})", serviceDate);
		}
	}

	private Optional<LocalDate> resolveLatestValidatedTradingDate(LocalDate serviceDate) {
		LocalDate candidate = businessDayCalendar.previousBusinessDay(serviceDate);
		for (int attempt = 0; attempt < MAX_LOOKBACK_BUSINESS_DAYS; attempt++) {
			if (isValidatedTradingDate(candidate)) {
				return Optional.of(candidate);
			}
			candidate = businessDayCalendar.previousBusinessDay(candidate);
		}
		return Optional.empty();
	}

	private boolean isValidatedTradingDate(LocalDate tradingDate) {
		boolean hasValidatedImport = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(tradingDate)
			.stream()
			.anyMatch(marketDataImport -> marketDataImport.getStatus() == ImportStatus.SUCCESS
				|| marketDataImport.getStatus() == ImportStatus.PARTIAL_SUCCESS);
		return hasValidatedImport && stockCandleRepository.existsByTradingDate(tradingDate);
	}
}
