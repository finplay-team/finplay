// 08:25 일봉 아카이브 수집 배치를 즉시 한 번 실행하는 로컬 개발용 서비스 (local 프로필 전용)
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.dto.response.StockDailyImportTriggerResponse;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// 실제 일봉은 평일 08:25 배치가 종목별 빈 구간만 채운다. 그 시각을 기다리지 않거나, 앱이 그 시각에 꺼져 있어
// 건너뛴 날에 즉시 실데이터를 채우기 위한 트리거다 — StockReplayImportTriggerService와 같은 성격.
//
// KIS_APP_KEY·KIS_APP_SECRET가 설정돼 있어야 한다(빈 조건은 KisDailyCandleClientImpl.requireCredentials).
//
// StockDailyCandleCollector.collect()는 스스로 트랜잭션을 열지 않으므로(종목별 순차 HTTP 호출 동안 DB 커넥션을
// 점유하지 않기 위해) 이 서비스도 트랜잭션 밖에서 배치를 호출하고 DB 조회는 writer에 위임한다.
@Service
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class StockDailyImportTriggerService {

	private final StockDailyCandleCollector stockDailyCandleCollector;
	private final StockDailyImportTriggerWriter writer;
	private final BusinessDayCalendar businessDayCalendar;
	private final Clock clock;

	public StockDailyImportTriggerResponse trigger() {
		LocalDate serviceDate = LocalDate.now(clock);
		LocalDate targetEndDate = businessDayCalendar.previousBusinessDay(serviceDate);

		long countBefore = writer.countArchivedCandles();
		stockDailyCandleCollector.collect();
		long countAfter = writer.countArchivedCandles();
		long newlyCollected = countAfter - countBefore;

		MarketDataImport latestImport = writer.findLatestImport(targetEndDate).orElse(null);
		log.info("KIS 일봉 아카이브 실수집 트리거 완료 (targetEndDate={}, 신규 {}건, 누적 {}건, status={})", targetEndDate,
			newlyCollected, countAfter, latestImport == null ? "이력없음" : latestImport.getStatus());

		return new StockDailyImportTriggerResponse(
			serviceDate,
			targetEndDate,
			newlyCollected,
			countAfter,
			latestImport == null ? null : latestImport.getStatus().name(),
			latestImport == null ? null : latestImport.getFailureReason());
	}
}
