// 검증 완료된 최신 거래일을 골라 오늘의 StockReplaySession을 READY/FAILED로 확정하는 평일 08:40 KST 배치
package com.finplay.api.market.service;

import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.MarketDataImportRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 이 클래스가 하는 일과 하지 않는 일을 명확히 구분한다(spec.md MKT-005, plan.md "배치 실행 시각").
//  - 한다: MarketDataImport·StockCandle의 수집 결과를 확인해 "검증 완료된 최신 거래일"을 고르고, 오늘(service_date)의
//    StockReplaySession을 PREPARING→READY 또는 PREPARING→FAILED로 전환한다.
//  - 하지 않는다: StockCandle을 직접 저장·수정하지 않는다(수집은 KisHistoricalCandleCollector의 책임). OPEN·CLOSED
//    계산도 하지 않는다(StockReplayService가 Clock으로 조회 시점에 계산).
@Service
@RequiredArgsConstructor
@Slf4j
public class StockReplaySessionScheduler {

	// 무한 루프 방지용 탐색 상한일 뿐 비즈니스 임계치가 아니다 — 20영업일 보존 정책(이슈 #83, 아직 미구현)보다 넉넉한
	// 여유를 둔 값이다. 정상 운영에서는 직전 영업일 한 번의 조회로 대부분 해결된다.
	private static final int MAX_LOOKBACK_BUSINESS_DAYS = 30;
	private static final String NO_VALIDATED_DATA_FAILURE_REASON = "검증 완료된 거래일 데이터를 찾지 못했습니다.";
	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";

	// KisHistoricalCandleCollector·StockReplayService와 별개의 클래스가 각자의 목적(수집 대상 거래일 계산 vs. 재생세션
	// 개장 판정 vs. 검증 완료 거래일 폴백 탐색)으로 같은 리소스 파일을 읽는다 — 세 번째 중복이지만, 이미 테스트가 붙은
	// 두 클래스의 내부 구조를 이번 항목 범위에서 함께 리팩터링하지 않는다(공통화는 별도 판단 필요, conventions.md).
	private static final Set<LocalDate> HOLIDAYS_2026 = loadHolidays(HOLIDAYS_RESOURCE_PATH);

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final MarketDataImportRepository marketDataImportRepository;
	private final StockCandleRepository stockCandleRepository;
	private final Clock clock;

	// 평일 08:40 KST 실행 — KisHistoricalCandleCollector(08:10)가 남긴 수집 결과를 확인한 뒤 재생세션을 고정한다
	// (plan.md "배치 실행 시각").
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
			// 이미 READY·FAILED로 확정된 세션 — 배치가 같은 날 다시 실행돼도 원본 거래일을 바꾸지 않는다(멱등, MKT-002).
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

	// 직전 영업일부터 거슬러 올라가며 "검증 완료"(MarketDataImport가 SUCCESS·PARTIAL_SUCCESS이고 실제 StockCandle이
	// 존재)된 첫 거래일을 찾는다. 직전 영업일 데이터가 아직 없으면 그 전 영업일로 자연 폴백한다 — 별도 폴백 분기 없이
	// 같은 탐색 루프가 그대로 이어진다(spec.md MKT-005·plan.md "배치 실행 시각").
	private Optional<LocalDate> resolveLatestValidatedTradingDate(LocalDate serviceDate) {
		LocalDate candidate = previousBusinessDay(serviceDate);
		for (int attempt = 0; attempt < MAX_LOOKBACK_BUSINESS_DAYS; attempt++) {
			if (isValidatedTradingDate(candidate)) {
				return Optional.of(candidate);
			}
			candidate = previousBusinessDay(candidate);
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

	// from의 직전 영업일을 계산한다 — 주말·공휴일(리소스 파일 기준)을 건너뛴다.
	private static LocalDate previousBusinessDay(LocalDate from) {
		LocalDate candidate = from.minusDays(1);
		while (isWeekend(candidate) || HOLIDAYS_2026.contains(candidate)) {
			candidate = candidate.minusDays(1);
		}
		return candidate;
	}

	private static boolean isWeekend(LocalDate date) {
		DayOfWeek dayOfWeek = date.getDayOfWeek();
		return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
	}

	// 클래스패스 리소스 파일에서 공휴일 목록(한 줄에 yyyy-MM-dd, #으로 시작하는 줄은 주석)을 읽어 Set으로 반환한다.
	private static Set<LocalDate> loadHolidays(String resourcePath) {
		try (InputStream inputStream = StockReplaySessionScheduler.class.getResourceAsStream(resourcePath)) {
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
