package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockCandleDto;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackBatchService {

	private final StockReplayService stockReplayService;

	private final InstrumentService instrumentService;

	private final PriceMoveDetector priceMoveDetector;

	private final PriceMoveCardService priceMoveCardService;

	private final MarketBriefingService marketBriefingService;

	private final InstrumentNewsSummaryService instrumentNewsSummaryService;

	private final LlmCallStats llmCallStats;

	@Scheduled(cron = "${feedback.batch.cron}", zone = "Asia/Seoul")
	public void runPreMarketBatch() {
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			log.info("재생세션이 준비되지 않아 개장 전 배치를 건너뛴다.");
			return;
		}

		LocalDate originTradeDate = session.sourceTradingDate();
		List<Instrument> instruments = instrumentService.getRealInstrumentEntities(Market.STOCK);
		log.info("개장 전 배치를 시작한다. 원본 거래일={} 종목={}건", originTradeDate, instruments.size());

		long batchStartedNanos = System.nanoTime();
		llmCallStats.startScope();
		try {
			long stepStartedNanos = System.nanoTime();
			try {
				generateMarketBriefing(originTradeDate);
			} catch (RuntimeException ex) {
				log.warn("개장 전 브리핑 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={}", originTradeDate, ex);
			}
			logStepElapsed("브리핑", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			try {
				generateNewsSummaries(instruments, originTradeDate, NewsSummaryScope.PRE_MARKET);
			} catch (RuntimeException ex) {
				log.warn("종목 뉴스 요약 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={} 범위={}",
					originTradeDate, NewsSummaryScope.PRE_MARKET, ex);
			}
			logStepElapsed("전장 요약", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			List<InstrumentDetections> detections = detectAll(instruments, originTradeDate);
			logStepElapsed("탐지", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			confirmCards(detections, originTradeDate, PriceMoveEventType.OPENING_GAP);
			logStepElapsed("시가 갭 카드", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			confirmCards(detections, originTradeDate, PriceMoveEventType.INTRADAY);
			logStepElapsed("장중 카드", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			try {
				generateNewsSummaries(instruments, originTradeDate, NewsSummaryScope.FULL);
			} catch (RuntimeException ex) {
				log.warn("종목 뉴스 요약 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={} 범위={}",
					originTradeDate, NewsSummaryScope.FULL, ex);
			}
			logStepElapsed("종일 요약", stepStartedNanos);

			LlmCallStats.Snapshot llmCalls = llmCallStats.finishScope();
			log.info("개장 전 배치를 마쳤다. 원본 거래일={} 소요={}ms LLM호출={}건 LLM소요합={}ms",
				originTradeDate, elapsedMillis(batchStartedNanos), llmCalls.count(), llmCalls.totalMillis());
		} finally {
			llmCallStats.finishScope();
		}
	}

	private void logStepElapsed(String step, long stepStartedNanos) {
		log.info("개장 전 배치 단계를 마쳤다. 단계={} 소요={}ms", step, elapsedMillis(stepStartedNanos));
	}

	private long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	void generateMarketBriefing(LocalDate originTradeDate) {
		boolean created = marketBriefingService.generateStockBriefing(originTradeDate).isPresent();
		log.info("개장 전 브리핑 단계를 마쳤다. 원본 거래일={} 생성={}", originTradeDate, created);
	}

	void generateNewsSummaries(
		List<Instrument> instruments, LocalDate originTradeDate, NewsSummaryScope scope) {
		int created = 0;
		for (Instrument instrument : instruments) {
			try {
				if (instrumentNewsSummaryService
					.generateStockSummary(instrument, originTradeDate, scope)
					.isPresent()) {
					created++;
				}
			} catch (RuntimeException ex) {
				log.warn("요약 생성에 실패해 이 종목을 건너뛴다. 종목={} 원본 거래일={} 범위={}",
					instrument.getId(), originTradeDate, scope, ex);
			}
		}
		log.info("{} 요약 {}건을 생성했다. 원본 거래일={}", scope, created, originTradeDate);
	}

	private List<InstrumentDetections> detectAll(List<Instrument> instruments, LocalDate originTradeDate) {
		List<InstrumentDetections> detections = new ArrayList<>();
		for (Instrument instrument : instruments) {
			try {
				List<StockCandleDto> candles = stockReplayService.getFullDayCandles(instrument.getId(),
					originTradeDate);
				BigDecimal previousClose = stockReplayService
					.getPreviousTradingDayClose(instrument.getId(), originTradeDate)
					.orElse(null);
				detections.add(
					new InstrumentDetections(instrument, priceMoveDetector.detect(candles, previousClose)));
			} catch (RuntimeException ex) {
				log.warn("변동 구간 탐지에 실패해 이 종목을 건너뛴다. 종목={}", instrument.getId(), ex);
			}
		}
		return detections;
	}

	private void confirmCards(
		List<InstrumentDetections> detections, LocalDate originTradeDate, PriceMoveEventType eventType) {
		int created = 0;
		for (InstrumentDetections each : detections) {
			for (PriceMoveDetectionDto detection : each.detections()) {
				if (detection.eventType() != eventType) {
					continue;
				}
				try {
					if (priceMoveCardService
						.confirmStockCard(each.instrument(), originTradeDate, detection)
						.isPresent()) {
						created++;
					}
				} catch (RuntimeException ex) {
					log.warn("카드 확정에 실패해 이 카드를 건너뛴다. 종목={} 종류={} 구간시작={}",
						each.instrument().getId(), eventType, detection.windowStart(), ex);
				}
			}
		}
		log.info("{} 카드 {}건을 생성했다.", eventType, created);
	}

	private record InstrumentDetections(Instrument instrument, List<PriceMoveDetectionDto> detections) {

		private InstrumentDetections {
			detections = List.copyOf(detections);
		}
	}
}
