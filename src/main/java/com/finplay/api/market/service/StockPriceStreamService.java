// 주식 SSE 스트림(/api/stocks/stream)의 snapshot 구성과 매분 price·status 이벤트 push를 담당하는 서비스 (이슈 #19)
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.sse.MarketPriceEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.market.dto.sse.MarketStatusEvent;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.sse.SseEmitterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceStreamService {

	private static final DateTimeFormatter EVENT_ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

	private final InstrumentRepository instrumentRepository;
	private final PriceQueryService priceQueryService;
	private final StockPriceProvider stockPriceProvider;
	private final SseEmitterRegistry sseEmitterRegistry;
	private final Clock clock;

	// 심볼별 마지막으로 전송한 가격 상태 — 매분 새로 공개된 가격만 골라 push하기 위한 비교 기준 (인스턴스 단일, 회원별 복사본 아님).
	private final Map<String, PriceQuoteDto> lastKnownQuotes = new ConcurrentHashMap<>();
	private volatile StockMarketStatus lastKnownMarketStatus;

	// 서버 기동 시점의 현재 상태를 기준선으로 세팅해, 기동 직후 첫 스케줄 실행에서 "변경"으로 오판해 이벤트를 쏘지 않게 한다.
	@PostConstruct
	@Transactional(readOnly = true)
	public void initializeBaseline() {
		for (Instrument instrument : getStockInstruments()) {
			lastKnownQuotes.put(instrument.getSymbol(), priceQueryService.getPriceQuote(instrument.getId()));
		}
		lastKnownMarketStatus = stockPriceProvider.getMarketStatus();
	}

	// 구독 직후 전송할 snapshot — 주식 16종 전체(가격 없는 종목도 UNAVAILABLE로 포함), id 없음.
	@Transactional(readOnly = true)
	public MarketSnapshotEvent buildSnapshot() {
		List<Instrument> instruments = getStockInstruments();
		StockMarketStatus marketStatus = stockPriceProvider.getMarketStatus();
		LocalDate sourceTradingDate = null;
		List<InstrumentPriceSnapshot> prices = new ArrayList<>();
		for (Instrument instrument : instruments) {
			PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument.getId());
			if (sourceTradingDate == null) {
				sourceTradingDate = quote.sourceTradingDate();
			}
			prices.add(InstrumentPriceSnapshot.of(instrument.getSymbol(), quote.price(), quote.sourceTime(),
				quote.status()));
		}
		return new MarketSnapshotEvent(Market.STOCK, sourceTradingDate, marketStatus, LocalDateTime.now(clock),
			prices);
	}

	// 구독 직후 컨트롤러가 호출 — snapshot 1건을 해당 emitter에만 전송한다 (재접속 시에도 이 경로로 재전송된다).
	public void sendSnapshot(SseEmitter emitter) {
		send(emitter, SseEmitter.event().name("snapshot").data(buildSnapshot()));
	}

	// 매분 정각 실행 — 새로 공개된 가격만 price 이벤트로 push, 개장·마감 전환 시 status 이벤트를 1회만 push한다.
	@Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
	@Transactional(readOnly = true)
	public void publishScheduledUpdates() {
		StockMarketStatus currentMarketStatus = stockPriceProvider.getMarketStatus();
		for (Instrument instrument : getStockInstruments()) {
			String symbol = instrument.getSymbol();
			PriceQuoteDto currentQuote = priceQueryService.getPriceQuote(instrument.getId());
			PriceQuoteDto previousQuote = lastKnownQuotes.get(symbol);
			if (isNewlyRevealedPrice(previousQuote, currentQuote)) {
				broadcastPriceEvent(symbol, currentQuote, currentMarketStatus);
			}
			lastKnownQuotes.put(symbol, currentQuote);
		}

		if (currentMarketStatus != lastKnownMarketStatus) {
			broadcastStatusEvent(currentMarketStatus);
			lastKnownMarketStatus = currentMarketStatus;
		}
	}

	private boolean isNewlyRevealedPrice(PriceQuoteDto previous, PriceQuoteDto current) {
		if (current.status() != PriceStatus.AVAILABLE) {
			return false;
		}
		return previous == null || previous.status() != PriceStatus.AVAILABLE
			|| !Objects.equals(previous.sourceTime(), current.sourceTime());
	}

	private void broadcastPriceEvent(String symbol, PriceQuoteDto quote, StockMarketStatus marketStatus) {
		MarketPriceEvent payload = new MarketPriceEvent(Market.STOCK, symbol, quote.price(), quote.sourceTime(),
			LocalDateTime.now(clock), quote.sourceTradingDate(), marketStatus);
		String eventId = "STOCK:%s:%s".formatted(symbol, quote.sourceTime().format(EVENT_ID_TIME_FORMAT));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.STOCK)) {
			send(emitter, SseEmitter.event().name("price").id(eventId).data(payload));
		}
	}

	private void broadcastStatusEvent(StockMarketStatus marketStatus) {
		MarketStatusEvent payload = new MarketStatusEvent(Market.STOCK, null, marketStatus, null, null,
			LocalDateTime.now(clock));
		for (SseEmitter emitter : sseEmitterRegistry.getEmitters(Market.STOCK)) {
			send(emitter, SseEmitter.event().name("status").data(payload));
		}
	}

	private List<Instrument> getStockInstruments() {
		return instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK);
	}

	// emitter 하나의 전송 실패가 나머지 구독자의 push를 막지 않게 개별 처리한다. 실패한 emitter는 completeWithError로
	// 종료시켜 SseEmitterRegistry에 배선된 onError 콜백이 집합에서 제거하도록 위임한다 (이슈 #18 계약 재사용).
	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder) {
		try {
			emitter.send(eventBuilder);
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			emitter.completeWithError(e);
		}
	}
}
