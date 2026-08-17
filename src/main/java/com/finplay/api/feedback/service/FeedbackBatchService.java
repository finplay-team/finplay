// 개장 전에 하루치 콘텐츠를 미리 만드는 배치의 오케스트레이션 — 순서를 세우고 각 단계를 부르기만 한다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockCandleDto;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 크론 값은 {@code application.yml}의 {@code feedback.batch.cron}이고 정본은 spec §C-1, 생성 순서의 정본은
 * §C-6이다.
 *
 * <p><b>재생세션 확정 배치에 이어 붙이지 않는다</b>(FEED-004). {@code StockReplaySessionScheduler
 * .resolveTodaySession()}은 08:40 크론이고 {@code @Transactional}이라, 이어 붙이면 두 가지가 동시에 깨진다 —
 * 같은 시각 크론 둘은 <b>실행 순서가 보장되지 않아</b> 세션이 아직 {@code PREPARING}인 채 배치가 돌면 매일
 * 조용히 0건이 되고, LLM 호출(약 81건 × 20초)이 세션 확정 트랜잭션 안에 들어가 <b>최대 27분간 트랜잭션이
 * 열린 채</b> 유지된다. 그래서 별도 크론(08:45)으로 분리하고 시작 시 {@code READY}인지 먼저 확인한다.
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> 저장 경계는 카드 1건 단위로 {@code PriceMoveCardWriter}에
 * 있다 — 배치가 트랜잭션을 열면 위와 같은 문제를 이름만 바꿔 재현하게 된다.
 *
 * <p><b>분봉은 {@code getFullDayCandles}로 가져온다.</b> 기존 {@code getRevealedCandles}는 현재 재생 시각까지만
 * 주므로 08:45에 부르면 <b>항상 빈 목록</b>이고, 그것은 예외가 아니라 정상 응답이라 카드가 매일 0건이 되어도
 * 로그조차 남지 않는다(§코드 배치와 설정).
 *
 * <p><b>수집은 이 배치에 포함하지 않는다</b>(FEED-004). 수집은 기사가 나오는 당일에 상시로 도는 별도 스케줄이
 * 맡고(FEED-001), 이 배치는 이미 저장된 기사를 읽기만 한다 — 수집 경로를 여기서 부르지 않는다.
 */
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

	/**
	 * 개장 전 배치 진입점. 순서는 §C-6의 5단계 그대로다.
	 *
	 * <pre>
	 * 1. 브리핑            09:00 정각에 READY 여야 한다
	 * 2. PRE_MARKET 요약   09:00에 Part C가 열리는 즉시 필요하다
	 * 3. 시가 갭 카드      revealTime이 09:00이라 개장과 동시에 필요하다
	 * 4. 장중 카드         revealTime 게이트에 걸려 오전 중에 필요해진다
	 * 5. FULL 요약         15:30 이후에 쓰인다
	 * </pre>
	 *
	 * <p>LLM 호출이 약 81건이고 건당 타임아웃이 20초라 <b>꼬리 케이스에서 09:00을 넘길 수 있다.</b> 순서가
	 * 뒤면 개장 직후에 브리핑과 전장 요약이 비는데, 그 상태는 §C-4의 {@code EMPTY}로 정의돼 있어 오류가
	 * 아니지만 사용자에게는 빈 화면이다.
	 *
	 * <p><b>{@code zone}을 반드시 붙인다</b>(§C-1). 배포 JVM 기본 타임존이 UTC라 빠뜨리면 08:45 배치가
	 * <b>KST 17:45에 돌아 장중 내내 화면이 비고</b> 예외도 로그도 남지 않는다.
	 */
	@Scheduled(cron = "${feedback.batch.cron}", zone = "Asia/Seoul")
	public void runPreMarketBatch() {
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		// 재생세션이 READY가 아니면 아무것도 생성하지 않는다 (FEED-004·§실패 처리). 원본 거래일이 확정되지
		// 않은 상태라 어떤 거래일의 콘텐츠를 만들지 자체를 알 수 없다.
		if (!session.ready()) {
			log.info("재생세션이 준비되지 않아 개장 전 배치를 건너뛴다.");
			return;
		}

		LocalDate originTradeDate = session.sourceTradingDate();
		// 샌드박스 튜토리얼 종목은 제외한다 (이슈 #406) — 수집된 기사가 없어 산출물도 없지만, 그 전에
		// 종목당 LLM 호출이 그만큼 낭비된다.
		List<Instrument> instruments = instrumentService.getRealInstrumentEntities(Market.STOCK);
		log.info("개장 전 배치를 시작한다. 원본 거래일={} 종목={}건", originTradeDate, instruments.size());

		// 소요 시간은 전부 System.nanoTime()으로 잰다 — 이 저장소는 시각을 Clock으로 주입받고 테스트가 그것을
		// 고정 Clock으로 바꾸므로, Clock으로 재면 통합 테스트에서 항상 0이 나오면서 테스트는 통과한다(이슈 #198).
		long batchStartedNanos = System.nanoTime();
		llmCallStats.startScope();
		try {
			// 단계 하나가 실패해도 다음 단계로 넘어간다 (FEED-004·§실패 처리). 특히 PRE_MARKET 요약은 카드보다
			// 앞이라, 격리하지 않으면 요약 LLM이 한 번 터지는 날 그날 카드가 전부 만들어지지 않는다.
			long stepStartedNanos = System.nanoTime();
			try {
				generateMarketBriefing(originTradeDate);
			} catch (RuntimeException ex) {
				log.warn("개장 전 브리핑 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={}", originTradeDate, ex);
			}
			// 실패로 건너뛴 단계도 시간을 쓴다 — 측정은 성공 여부와 무관하게 단계마다 남긴다.
			logStepElapsed("브리핑", stepStartedNanos);

			stepStartedNanos = System.nanoTime();
			try {
				generateNewsSummaries(instruments, originTradeDate, NewsSummaryScope.PRE_MARKET);
			} catch (RuntimeException ex) {
				log.warn("종목 뉴스 요약 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={} 범위={}",
					originTradeDate, NewsSummaryScope.PRE_MARKET, ex);
			}
			logStepElapsed("전장 요약", stepStartedNanos);

			// 탐지는 §C-6의 5단계에 없다 — LLM을 부르지 않는 서버 계산이며 3·4단계의 입력을 만드는 준비 작업이다.
			// 종목마다 두 번 탐지하지 않으려고 여기서 한 번에 계산해 두 단계가 나눠 쓴다.
			stepStartedNanos = System.nanoTime();
			List<InstrumentDetections> detections = detectAll(instruments, originTradeDate);
			// LLM을 부르지 않는 단계라 여기가 길면 원인이 다른 곳(분봉 조회)이다 — 그래서 카드와 나눠 잰다.
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
			// 정상 종료면 위에서 이미 닫혀 여기 호출은 빈 스냅샷이다. 단계별 격리를 뚫고 나온 실패(현재는 Error
			// 계열뿐이지만 그 격리가 뒤에 바뀔 수 있다)에서만 실제로 닫는다 — 스코프를 연 채 빠져나가면 그 워커
			// 스레드에 다음 배치까지 남는다 (PR #202 리뷰 권장 ①).
			// 종료 로그를 여기로 옮기지 않는 이유는 "마쳤다"가 중단된 실행에도 찍히면 안 되기 때문이다.
			llmCallStats.finishScope();
		}
	}

	/**
	 * 단계 하나의 소요 시간을 남긴다. 08:45 배치가 09:00 마감을 지키는지는 총 시간만으로는 알 수 없고, 넘겼을 때
	 * <b>어디를 손봐야 하는지</b>는 단계별 시간에만 있다(이슈 #198).
	 *
	 * <p>{@code System.nanoTime()} 뺄셈과 로깅뿐이라 이 메서드는 예외를 던질 수 없다 — 측정이 배치를 멈추지
	 * 않는다는 조건을 {@code try/catch}가 아니라 이 형태로 지킨다.
	 */
	private void logStepElapsed(String step, long stepStartedNanos) {
		log.info("개장 전 배치 단계를 마쳤다. 단계={} 소요={}ms", step, elapsedMillis(stepStartedNanos));
	}

	private long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	/**
	 * <b>1단계 — 개장 전 브리핑.</b> 확정은 {@code MarketBriefingService}가 하고 여기서는 부르기만 한다(§C-6).
	 *
	 * <p>자리를 <b>메서드로</b> 둔 이유는 순서가 완료 조건(배치 ③)이기 때문이다 — 주석으로 남기면 단정할 대상이
	 * 없어 순서가 뒤에 조용히 바뀐다. 호출 위치·시그니처는 바꾸지 않는다.
	 *
	 * <p><b>브리핑은 호출 1건이라 본문에 추가 격리를 두지 않는다.</b> 호출부가 이 단계를 {@code try/catch}로
	 * 감싸 두었으므로 여기서 던져도 뒤 단계는 계속된다 — 종목 루프가 있는 요약과 달리 여기서 더 쪼갤 단위가 없다.
	 */
	void generateMarketBriefing(LocalDate originTradeDate) {
		// 비어 있는 결과는 오류가 아니다 — 이미 있거나(재실행) 전장 기사가 0건인 날이다(FEED-009).
		boolean created = marketBriefingService.generateStockBriefing(originTradeDate).isPresent();
		log.info("개장 전 브리핑 단계를 마쳤다. 원본 거래일={} 생성={}", originTradeDate, created);
	}

	/**
	 * <b>2·5단계 — 종목 뉴스 요약.</b> 확정은 {@code InstrumentNewsSummaryService}가 종목 1건씩 한다(§C-6).
	 *
	 * <p><b>{@code PRE_MARKET}과 {@code FULL}을 한 메서드로 받되 호출은 두 자리로 나뉜다</b> — 범위가 다른
	 * 두 요약을 같은 시점에 만들면 순서를 정한 의미가 없다. {@code FULL}은 15:30 이후에만 쓰이므로 마지막이고,
	 * 09:00에 노출되면 <b>요약 한 문장이 그날 오후를 통째로 알려준다</b>(FEED-008). 범위를 인자로 받으므로
	 * 본문에 분기를 두지 않는다.
	 *
	 * <p><b>격리를 종목 단위로 내린다.</b> 호출부의 {@code try/catch}는 단계 단위 방어선이라, 한 종목이 터지면
	 * <b>그 범위의 나머지 종목이 통째로 날아간다</b> — 그것도 예외 없이 조용히, 로그 한 줄만 남기고. 개장 전
	 * 시간대에 종목 하나의 LLM 호출이 실패하는 것은 드문 일이 아니므로 여기서 종목마다 접는다
	 * ({@code detectAll}·{@code confirmCards}와 같은 형태).
	 */
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

	/**
	 * 종목별로 변동 구간을 탐지한다. 한 종목이 실패해도 나머지는 계속한다(§실패 처리).
	 *
	 * <p>분봉이 없거나 σ가 0이면 빈 결과이고 <b>오류가 아니다</b>(FEED-002). 직전 거래일 종가가 없으면 시가 갭
	 * 카드만 생략된다 — {@code Optional.empty()}를 {@code null}로 바꿔 넘기는 것이 탐지기와의 계약이다.
	 */
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

	/**
	 * 한 종류의 카드를 <b>전 종목에 대해</b> 확정한다.
	 *
	 * <p><b>종목 루프 안에서 두 종류를 함께 만들지 않는다.</b> 그렇게 하면 실행이 길어졌을 때 뒤쪽 종목의
	 * 시가 갭 카드가 09:00까지 만들어지지 못하는데, 갭 카드는 개장과 동시에 필요한 산출물이다(§C-6의 생성 순서).
	 * 종류로 한 바퀴씩 도는 이 형태가 그 순서를 종목 수와 무관하게 보장한다.
	 *
	 * <p><b>카드 하나가 실패해도 다음으로 넘어간다</b>(FEED-004·§실패 처리). 배치 전체를 실패시키지 않는다 —
	 * LLM 호출 실패·후검증 적발은 {@code NarrativeService}가 템플릿으로 흡수하지만, 그 밖의 실패(외부 호출이
	 * 예외로 터지는 경우 등)는 여기서 카드 단위로 격리한다.
	 */
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

	// 탐지 결과를 종목과 함께 들고 다니기 위한 것뿐이다. 카드 확정에 Instrument 엔티티가 필요한데(§C-6 —
	// feedback은 market 리포지토리를 직접 주입하지 않는다) id만 남기면 두 단계에서 다시 조회해야 한다.
	private record InstrumentDetections(Instrument instrument, List<PriceMoveDetectionDto> detections) {

		private InstrumentDetections {
			detections = List.copyOf(detections);
		}
	}
}
