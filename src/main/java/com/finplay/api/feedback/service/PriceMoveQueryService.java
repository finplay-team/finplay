// 종목별 변동 원인 카드 목록을 노출 게이트에 맞춰 조회하는 읽기 전용 서비스.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PriceMoveItem;
import com.finplay.api.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

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
		// 그 분기는 코인 탐지·감시와 함께 plan.md 8번이 이 자리에 더한다 — 지금은 카드가 생성되지 않으므로 빈
		// 목록이 정확한 답이고, 여기서 예외를 던지면 코인 종목 상세 화면이 통째로 오류가 된다.
		if (instrument.getMarket() == Market.CRYPTO) {
			return PriceMoveListResponse.empty();
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

		Map<Long, List<NewsItem>> sourcesByEventId = findSources(events);
		List<PriceMoveItem> moves = events.stream()
			.map(event -> PriceMoveItem.ofStock(
				event, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
		return PriceMoveListResponse.of(session.sourceTradingDate(), moves);
	}

	// 카드마다 따로 묻지 않고 한 번에 읽어 카드 id로 묶는다. 쿼리가 발행시각 내림차순이라 각 목록의 순서도
	// 그대로 유지된다 (LinkedHashMap·ArrayList가 삽입 순서를 지킨다).
	private Map<Long, List<NewsItem>> findSources(List<PriceMoveEvent> events) {
		List<Long> eventIds = events.stream().map(PriceMoveEvent::getId).toList();
		Map<Long, List<NewsItem>> sourcesByEventId = new LinkedHashMap<>();
		for (PriceMoveEventSource source : priceMoveEventSourceRepository
			.findAllByPriceMoveEventIdIn(eventIds)) {
			sourcesByEventId
				.computeIfAbsent(source.getPriceMoveEvent().getId(), id -> new ArrayList<>())
				.add(NewsItem.from(source.getMarketNewsItem()));
		}
		return sourcesByEventId;
	}
}
