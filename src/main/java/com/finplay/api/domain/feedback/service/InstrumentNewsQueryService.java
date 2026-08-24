// 종목의 뉴스·공시 목록과 그 시점의 요약을 노출 게이트에 맞춰 조립하는 조회 서비스 — DB는 Reader가, 캐시는 FeedbackQueryCache가 맡고 여기는 순서만 잡는다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.store.FeedbackQueryCache;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계약은 {@code docs/api/feedback.md}의 "종목 뉴스 목록·요약 조회" 행, 상태값과 판정 순서는 spec §C-4,
 * 구간과 {@code summaryScope} 대응은 §C-2, 공시 날짜 판정은 §C-3, 노출 게이트는 §C-5가 정본이다.
 *
 * <p><b>쓰지 않는다.</b> 요약은 전 회원이 공유하는 배치 산출물이라 조회가 만들지 않는다 — GET이 LLM을
 * 호출하지도 DB에 쓰지도 않는다({@code docs/conventions/code.md}, FEED-008). 배치로 옮긴 이유는 비용이 아니라
 * <b>스포일러 차단과 첫 사용자의 대기</b>다.
 *
 * <p><b>어느 상태값이든 200이다</b>(FEED-008). 없는 종목만 404이며 그 판정은 {@code InstrumentService}가 한다.
 *
 * <p><b>이 클래스에 {@code @Transactional}이 없는 것이 설계다.</b> {@code PostSellFeedbackService}가 LLM 호출을
 * 트랜잭션 밖에 두려고 {@code Reader}로 나눈 것과 같은 형태이며, 여기서 밖에 두려는 것은 조회 캐시의 락 대기다.
 *
 * <pre>
 * 1. reader.readMarket / readStockItems / readStockSummary  @Transactional(readOnly = true) — 읽고 바로 닫는다
 * 2. feedbackQueryCache.getOrLoad...                        트랜잭션 없음 — 최대 wait-millis 대기가 여기 있다
 * 3. 상태값 판정과 응답 조립                                 순수 로직
 * </pre>
 *
 * 조회 전체를 한 트랜잭션으로 감싸면 2번의 대기 동안 JDBC 커넥션을 쥐고 있게 되고, 만료 경계에 요청이 몰리는
 * 순간 대기 스레드가 풀을 채워 뒤따르는 요청이 커넥션 획득에서 막힌다 — 캐시가 막으려던 것보다 나쁜 실패다.
 */
@Service
@RequiredArgsConstructor
public class InstrumentNewsQueryService {

	private final InstrumentNewsQueryReader instrumentNewsQueryReader;

	private final StockReplayService stockReplayService;

	// 요약 텍스트만 이 캐시를 거친다. items 수집은 §C-5 노출 게이트의 구현이라 캐시하지 않는다(ADR-0015 §1).
	private final FeedbackQueryCache feedbackQueryCache;

	private final Clock clock;

	/**
	 * 종목의 기사 목록과 그 시각의 요약을 조회한다.
	 *
	 * <p><b>판정 순서는 §C-4의 표 그대로다.</b> 순서를 바꾸면 두 조건이 동시에 성립하는 구간(재생세션 미준비
	 * + 개장 전)에서 값이 갈린다 — 1번이 {@code originTradeDate}까지 {@code null}인데 2번은 채우기 때문이다.
	 *
	 * <pre>
	 * 1. 재생세션 미준비 → NOT_YET, originTradeDate=null
	 * 2. 09:00 이전     → NOT_YET, originTradeDate 채움
	 * 3. 기사 0건       → EMPTY,   items=[]
	 * 4. 요약 행 없음   → EMPTY,   items 채움
	 * 5. 행은 있고 summary가 null → UNAVAILABLE, items 채움
	 * 6. 그 외          → READY
	 * </pre>
	 *
	 * @param instrumentId 없는 종목이면 {@code InstrumentService}가 404({@code NOT_FOUND})로 거절한다
	 */
	public InstrumentNewsResponse getInstrumentNews(Long instrumentId) {
		if (instrumentNewsQueryReader.readMarket(instrumentId) == Market.CRYPTO) {
			return getCryptoNews(instrumentId);
		}

		// 1번 — 원본 거래일 자체가 확정되지 않은 상태라 날짜를 지어낼 수 없다.
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return InstrumentNewsResponse.notYet(null);
		}

		// 2번 — Part D와 같은 전장 기사군을 다루므로 하한을 맞춘다. 없으면 08:41에 이 API로 조회해
		// 브리핑이 09:00까지 감추는 기사를 20분 먼저 볼 수 있다 (FEED-008).
		LocalDate originTradeDate = session.sourceTradingDate();
		LocalTime now = LocalTime.now(clock);
		if (now.isBefore(MarketSessionTimes.MARKET_OPEN_TIME)) {
			return InstrumentNewsResponse.notYet(originTradeDate);
		}

		NewsSummaryScope scope = resolveScope(now);
		List<NewsItem> items = instrumentNewsQueryReader.readStockItems(instrumentId, originTradeDate, scope, now);

		// 3번 — 기사가 0건이면 요약 행이 있든 없든 EMPTY다. 행 조회보다 앞이라 순서를 바꾸면
		// "기사도 없고 서술도 없는" 날이 UNAVAILABLE로 보인다.
		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.EMPTY, null, List.of());
		}

		// 4·5·6번 — 요약 텍스트만 캐시를 거친다(ADR-0015 §1). items는 위에서 이미 매 요청 DB로 모았다.
		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadStockSummaryText(
			instrumentId, originTradeDate, scope,
			() -> {
				SummaryTextLookupDto lookup = instrumentNewsQueryReader
					.readStockSummary(instrumentId, originTradeDate, scope);
				summaryRowFound.set(lookup.rowExists());
				return lookup.readyText();
			});
		// 캐시는 서술이 있는 값(READY)만 담으므로 적중은 곧 6번이다.
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.READY, text.get(), items);
		}

		// 미적중이면 위 로더가 반드시 실행됐다(캐시는 값이 없으면 항상 로더를 부른다). 그 DB 결과로 4·5번을
		// 지금 로직 그대로 가른다 — 저장된 행만으로는 둘이 구분되지 않으므로(둘 다 summary가 NULL) 행 존재
		// 여부와 함께 갈라야 한다. 판정 순서는 §C-4 그대로다.
		return InstrumentNewsResponse.of(
			originTradeDate,
			scope,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	/**
	 * 코인 종목 조회 — 최근 24시간 기사와 {@code generated_at} 최신 1행이다 (FEED-008).
	 *
	 * <p><b>주식의 게이트·판정 순서를 타지 않는다.</b> 코인은 재생세션과 무관하고 '개장 전'이라는 시점이 없어
	 * §C-4의 1·2번이 성립하지 않는다 — {@code NOT_YET}이 되지 않는다. 3~6번은 그대로 쓴다.
	 *
	 * <p><b>{@code summaryScope}는 언제나 {@code ROLLING_24H}이고 {@code originTradeDate}는 {@code null}이다</b>
	 * (§C-2·§C-9). 저장된 행의 {@code origin_trade_date}에는 값이 있지만 그것은 유니크 축을 성립시키려고 채운
	 * <b>배치 실행 날짜</b>이지 거래일이 아니다.
	 *
	 * <p><b>{@code items}의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 최대 65분 어긋난다 —
	 * 허용된 동작이다</b>(FEED-008). 맞추려고 조회 시 생성으로 되돌아가지 않는다.
	 */
	private InstrumentNewsResponse getCryptoNews(Long instrumentId) {
		List<NewsItem> items = instrumentNewsQueryReader.readCryptoItems(instrumentId, LocalDateTime.now(clock));
		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.EMPTY, null, List.of());
		}

		// 주식과 같은 형태다 — 요약 텍스트만 캐시를 거치고 items의 24시간 창은 그대로 매 요청 DB로 간다.
		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadCryptoSummaryText(instrumentId, () -> {
			SummaryTextLookupDto lookup = instrumentNewsQueryReader.readLatestCryptoSummary(instrumentId);
			summaryRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.READY, text.get(), items);
		}

		return InstrumentNewsResponse.of(
			null,
			NewsSummaryScope.ROLLING_24H,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	/**
	 * 조회 시각이 보는 요약 범위 (§C-2).
	 *
	 * <p><b>{@code FULL}을 09:00에 노출하면 안 된다</b>(FEED-008). 하루 전체를 요약한 문장은 장중 기사를
	 * 언급하므로 {@code items}에 게이트를 걸어도 <b>요약 한 문장이 그날 오후를 통째로 알려준다.</b>
	 */
	private static NewsSummaryScope resolveScope(LocalTime now) {
		return now.isBefore(MarketSessionTimes.MARKET_CLOSE_TIME)
			? NewsSummaryScope.PRE_MARKET
			: NewsSummaryScope.FULL;
	}
}
