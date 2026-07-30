// 08:10 수집 배치와 08:40 세션 확정 배치를 즉시 한 번 실행하는 로컬 개발용 서비스 (local 프로필 전용)
package com.finplay.api.market.service;

import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.dto.response.StockReplayImportTriggerResponse;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// 실제 분봉은 평일 08:10 배치가 수집하고 08:40 배치가 재생세션을 확정한다. 그 시각을 기다리지 않거나, 앱이 그 시각에
// 꺼져 있어 건너뛴 날에 즉시 실데이터를 채우기 위한 트리거다.
//
// KIS_APP_KEY·KIS_APP_SECRET가 설정돼 있어야 하고, 앱키 환경(실전/모의)과 kis.base-url 도메인이 짝이 맞아야 한다 —
// 어긋나면 토큰 발급까지는 성공하지만 업무 API가 EGW02004로 거부한다.
//
// collect()는 스스로 트랜잭션을 열지 않는다 (16종목 순차 HTTP 호출 동안 DB 커넥션을 점유하지 않기 위해, PR #94 리뷰).
// 그 설계를 깨지 않도록 이 서비스도 트랜잭션 밖에서 배치를 호출하고 DB 조회는 writer에 위임한다.
@Service
@Profile("local")
@RequiredArgsConstructor
@Slf4j
public class StockReplayImportTriggerService {

	private static final String KIS_DATA_SOURCE = "KIS";

	private final KisHistoricalCandleCollector kisHistoricalCandleCollector;
	private final StockReplaySessionScheduler stockReplaySessionScheduler;
	private final StockReplayImportTriggerWriter writer;
	private final StockPriceProvider stockPriceProvider;
	private final BusinessDayCalendar businessDayCalendar;
	private final Clock clock;

	public StockReplayImportTriggerResponse trigger() {
		LocalDate serviceDate = LocalDate.now(clock);
		LocalDate tradingDate = businessDayCalendar.previousBusinessDay(serviceDate);

		kisHistoricalCandleCollector.collect();
		stockReplaySessionScheduler.resolveTodaySession();

		long collected = writer.countCandles(tradingDate, KIS_DATA_SOURCE);
		StockReplaySession session = writer.findSession(serviceDate).orElse(null);
		String marketStatus = stockPriceProvider.getMarketStatus().name();
		log.info("KIS 실수집 트리거 완료 (tradingDate={}, KIS 분봉 {}건, 세션={}, marketStatus={})", tradingDate, collected,
			session == null ? "없음" : session.getPreparationStatus(), marketStatus);

		return new StockReplayImportTriggerResponse(
			serviceDate,
			tradingDate,
			collected,
			session == null ? null : session.getPreparationStatus().name(),
			session == null ? null : session.getFailureReason(),
			marketStatus);
	}
}
