// 종목별 변동 원인 카드 목록을 노출 게이트에 맞춰 조회하는 읽기 전용 서비스.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PriceMoveItem;
import com.finplay.api.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약은 {@code docs/api-contracts.md}의 "종목 변동 원인 카드 조회" 행이고 노출 게이트는 spec §C-5다.
 *
 * <p><b>빈 응답이 오류가 아니다</b>(FEED-006·§실패 처리). 카드 0건도, 재생세션 미준비도 200이다 — 후자는
 * {@code originTradeDate}까지 {@code null}이다.
 *
 * <p><b>쓰지 않는다.</b> 카드·요약은 전 회원이 공유하는 배치 산출물이라 조회가 만들지 않는다
 * ({@code docs/conventions.md} — GET은 부수효과 없음). 원장 불변이 조회 경로에서 취하는 형태다.
 */
@Service
@RequiredArgsConstructor
public class PriceMoveQueryService {

	private final InstrumentService instrumentService;

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveSourceLoader priceMoveSourceLoader;

	private final FeedbackCryptoProperties cryptoProperties;

	private final Clock clock;

	/**
	 * 종목의 변동 원인 카드를 조회한다.
	 *
	 * @param instrumentId 없는 종목이면 {@code InstrumentService}가 404({@code NOT_FOUND})로 거절한다
	 * @return 노출 시각이 지난 카드만 담은 목록. 재생세션이 {@code READY}가 아니면
	 *     {@code originTradeDate=null}·빈 배열이며 <b>오류가 아니다</b>
	 */
	@Transactional(readOnly = true)
	public PriceMoveListResponse getPriceMoves(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		// 코인은 재생 시간축이 없어 원본 거래일이 아니라 "최근 24시간"으로 조회하고 노출 게이트도 없다(§C-2·§C-5).
		if (instrument.getMarket() == Market.CRYPTO) {
			return getCryptoPriceMoves(instrument);
		}

		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return PriceMoveListResponse.empty();
		}

		// 게이트 (§C-5) — reveal_time이 TIME이라 오늘 벽시계 시각과 비교하는 것이 곧 "서비스 날짜 + reveal_time".
		// 재생이 1배속이라 원본 거래일 시각과 서비스 날짜의 벽시계 시각이 1:1로 대응하며, 별도 오프셋이 없다.
		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				instrumentId, session.sourceTradingDate(), LocalTime.now(clock));
		if (events.isEmpty()) {
			return PriceMoveListResponse.of(session.sourceTradingDate(), List.of());
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		List<PriceMoveItem> moves = events.stream()
			.map(event -> PriceMoveItem.ofStock(
				event, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
		return PriceMoveListResponse.of(session.sourceTradingDate(), moves);
	}

	/**
	 * 코인의 "최근 24시간" 카드 조회 (§C-2 {@code ROLLING_24H}). 노출 게이트가 없다(§C-5 "카드(코인) — 없음") —
	 * {@code reveal_time}을 보지 않는다.
	 *
	 * @return {@code originTradeDate}는 <b>항상 {@code null}</b>이다(§C-2) — 코인은 실시간이라 원본 거래일
	 *     개념이 없다
	 */
	private PriceMoveListResponse getCryptoPriceMoves(Instrument instrument) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				instrument.getId(), Market.CRYPTO, now.minusHours(24), now);
		if (events.isEmpty()) {
			return PriceMoveListResponse.of(null, List.of());
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		List<PriceMoveItem> moves = events.stream()
			.map(event -> PriceMoveItem.ofCrypto(
				event,
				sourcesByEventId.getOrDefault(event.getId(), List.of()),
				cryptoProperties.rollingWindowMinutes()))
			.toList();
		return PriceMoveListResponse.of(null, moves);
	}

}
