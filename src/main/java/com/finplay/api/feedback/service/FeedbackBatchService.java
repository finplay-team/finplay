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
		List<Instrument> instruments = instrumentService.getInstrumentEntities(Market.STOCK);
		log.info("개장 전 배치를 시작한다. 원본 거래일={} 종목={}건", originTradeDate, instruments.size());

		// 단계 하나가 실패해도 다음 단계로 넘어간다 (FEED-004·§실패 처리). 특히 PRE_MARKET 요약은 카드보다
		// 앞이라, 격리하지 않으면 요약 LLM이 한 번 터지는 날 그날 카드가 전부 만들어지지 않는다.
		try {
			generateMarketBriefing(originTradeDate);
		} catch (RuntimeException ex) {
			log.warn("개장 전 브리핑 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={}", originTradeDate, ex);
		}
		try {
			generateNewsSummaries(instruments, originTradeDate, NewsSummaryScope.PRE_MARKET);
		} catch (RuntimeException ex) {
			log.warn("종목 뉴스 요약 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={} 범위={}",
				originTradeDate, NewsSummaryScope.PRE_MARKET, ex);
		}

		// 탐지는 §C-6의 5단계에 없다 — LLM을 부르지 않는 서버 계산이며 3·4단계의 입력을 만드는 준비 작업이다.
		// 종목마다 두 번 탐지하지 않으려고 여기서 한 번에 계산해 두 단계가 나눠 쓴다.
		List<InstrumentDetections> detections = detectAll(instruments, originTradeDate);
		confirmCards(detections, originTradeDate, PriceMoveEventType.OPENING_GAP);
		confirmCards(detections, originTradeDate, PriceMoveEventType.INTRADAY);

		try {
			generateNewsSummaries(instruments, originTradeDate, NewsSummaryScope.FULL);
		} catch (RuntimeException ex) {
			log.warn("종목 뉴스 요약 생성에 실패해 이 단계를 건너뛴다. 원본 거래일={} 범위={}",
				originTradeDate, NewsSummaryScope.FULL, ex);
		}
		log.info("개장 전 배치를 마쳤다. 원본 거래일={}", originTradeDate);
	}

	/**
	 * <b>1단계 — 개장 전 브리핑.</b> 내용 생성은 {@code plan.md} 5번(FEED-009) 소유이므로 이 이슈는 순서상의
	 * 자리만 세운다.
	 *
	 * <p>5번은 {@code MarketBriefingService}를 주입해 이 메서드의 본문을 채우면 되고 호출 위치는 건드리지
	 * 않는다. 자리를 <b>메서드로</b> 남긴 이유는 순서가 이 이슈의 완료 조건(배치 ③)이기 때문이다 — 주석으로
	 * 남기면 단정할 대상이 없어 순서가 뒤에 조용히 바뀐다.
	 *
	 * <p>호출부가 이 단계를 {@code try/catch}로 감싸 두었으므로 <b>여기서 던져도 뒤 단계는 계속된다.</b>
	 * 그것은 단계 단위 방어선이라, 본문을 채울 때 {@code detectAll}·{@code confirmCards}처럼 <b>더 작은 단위</b>
	 * (브리핑은 호출 1건, 요약은 종목 1건)의 격리가 필요하면 본문 안에 함께 넣는다.
	 */
	void generateMarketBriefing(LocalDate originTradeDate) {
		log.debug("개장 전 브리핑 생성은 아직 구현되지 않았다 (plan.md 5번). 원본 거래일={}", originTradeDate);
	}

	/**
	 * <b>2·5단계 — 종목 뉴스 요약.</b> 내용 생성은 {@code plan.md} 5번(FEED-008) 소유다.
	 *
	 * <p><b>{@code PRE_MARKET}과 {@code FULL}을 한 메서드로 받되 호출은 두 자리로 나뉜다</b> — 범위가 다른
	 * 두 요약을 같은 시점에 만들면 순서를 정한 의미가 없다. {@code FULL}은 15:30 이후에만 쓰이므로 마지막이고,
	 * 09:00에 노출되면 <b>요약 한 문장이 그날 오후를 통째로 알려준다</b>(FEED-008).
	 *
	 * <p>두 호출 자리 모두 호출부가 {@code try/catch}로 감싸 두었다 — {@code PRE_MARKET}이 카드보다 앞이라
	 * 격리가 없으면 요약 하나가 터지는 날 <b>그날 카드가 전부 만들어지지 않는다.</b> 본문을 채울 때 종목 단위
	 * 격리가 필요하면 {@code detectAll}처럼 본문 안에 함께 넣는다.
	 */
	void generateNewsSummaries(
		List<Instrument> instruments, LocalDate originTradeDate, NewsSummaryScope scope) {
		log.debug("종목 뉴스 요약 생성은 아직 구현되지 않았다 (plan.md 5번). 원본 거래일={} 범위={} 종목={}건",
			originTradeDate, scope, instruments.size());
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
