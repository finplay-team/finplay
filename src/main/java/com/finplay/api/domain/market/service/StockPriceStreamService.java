package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.dto.sse.MarketPriceEvent;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent;
import com.finplay.api.domain.market.dto.sse.MarketSnapshotEvent.InstrumentPriceSnapshot;
import com.finplay.api.domain.market.dto.sse.MarketStatusEvent;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.sse.SseEmitterRegistry;
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
import org.springframework.transaction.support.TransactionTemplate;
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
	private final TransactionTemplate transactionTemplate;

	private final Map<String, PriceQuoteDto> lastKnownQuotes = new ConcurrentHashMap<>();
	private volatile StockMarketStatus lastKnownMarketStatus;

	@PostConstruct
	public void initializeBaseline() {
		for (Instrument instrument : getStockInstruments()) {
			lastKnownQuotes.put(instrument.getSymbol(), priceQueryService.getPriceQuote(instrument));
		}
		lastKnownMarketStatus = stockPriceProvider.getMarketStatus();
	}

	public MarketSnapshotEvent buildSnapshot() {
		List<Instrument> instruments = getStockInstruments();
		StockMarketStatus marketStatus = stockPriceProvider.getMarketStatus();
		LocalDate sourceTradingDate = null;
		List<InstrumentPriceSnapshot> prices = new ArrayList<>();
		for (Instrument instrument : instruments) {
			PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument);
			if (sourceTradingDate == null) {
				sourceTradingDate = quote.sourceTradingDate();
			}
			prices.add(InstrumentPriceSnapshot.of(instrument.getSymbol(), quote.price(), quote.sourceTime(),
				quote.status()));
		}
		return new MarketSnapshotEvent(Market.STOCK, sourceTradingDate, marketStatus, LocalDateTime.now(clock),
			prices);
	}

	@Transactional(readOnly = true)
	public void sendSnapshot(SseEmitter emitter) {
		send(emitter, SseEmitter.event().name("snapshot").data(buildSnapshot()));
	}

	public SseEmitter createEmitter() {
		return sseEmitterRegistry.createEmitter(Market.STOCK);
	}

	public void activate(SseEmitter emitter) {
		sseEmitterRegistry.activate(Market.STOCK, emitter);
	}

	@Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
	public void publishScheduledUpdates() {
		ScheduledUpdate update = transactionTemplate.execute(status -> collectScheduledUpdate());
		for (InstrumentPriceUpdate priceUpdate : update.priceUpdates()) {
			broadcastPriceEvent(priceUpdate.symbol(), priceUpdate.quote(), update.marketStatus());
		}
		if (update.marketStatusChanged()) {
			broadcastStatusEvent(update.marketStatus());
			lastKnownMarketStatus = update.marketStatus();
		}
	}

	private ScheduledUpdate collectScheduledUpdate() {
		StockMarketStatus currentMarketStatus = stockPriceProvider.getMarketStatus();
		List<InstrumentPriceUpdate> priceUpdates = new ArrayList<>();
		for (Instrument instrument : getStockInstruments()) {
			String symbol = instrument.getSymbol();
			PriceQuoteDto currentQuote = priceQueryService.getPriceQuote(instrument);
			PriceQuoteDto previousQuote = lastKnownQuotes.get(symbol);
			if (isNewlyRevealedPrice(previousQuote, currentQuote)) {
				priceUpdates.add(new InstrumentPriceUpdate(symbol, currentQuote));
			}
			lastKnownQuotes.put(symbol, currentQuote);
		}
		boolean marketStatusChanged = currentMarketStatus != lastKnownMarketStatus;
		return new ScheduledUpdate(priceUpdates, currentMarketStatus, marketStatusChanged);
	}

	private record InstrumentPriceUpdate(String symbol, PriceQuoteDto quote) {
	}

	private record ScheduledUpdate(List<InstrumentPriceUpdate> priceUpdates, StockMarketStatus marketStatus,
		boolean marketStatusChanged) {
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

	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder) {
		try {
			emitter.send(eventBuilder);
		} catch (IOException | RuntimeException e) {
			log.debug("SSE 이벤트 전송 실패로 emitter 종료", e);
			emitter.completeWithError(e);
		}
	}
}
