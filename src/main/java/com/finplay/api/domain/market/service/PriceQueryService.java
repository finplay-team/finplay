package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriceQueryService {

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;
	private final PriceStore priceStore;
	private final TutorialSampleInstrumentPriceService tutorialSampleInstrumentPriceService;

	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Long instrumentId) {
		return requireAvailable(getPriceQuote(instrumentId));
	}

	@Transactional(readOnly = true)
	public OrderExecutionPriceDto getOrderExecutionPrice(Instrument instrument) {
		if (instrument.isTutorialSample()) {
			return new OrderExecutionPriceDto(tutorialSampleInstrumentPriceService.getPriceQuote(instrument), null);
		}
		if (instrument.getMarket() == Market.CRYPTO) {
			return new OrderExecutionPriceDto(requireAvailable(getCryptoDisplayPriceQuote(instrument)), null);
		}

		StockReplayPriceDto stockQuote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (stockQuote.marketStatus() == StockMarketStatus.CLOSED) {
			throw new BusinessException(ErrorCode.MARKET_CLOSED);
		}
		if (stockQuote.replaySession() == null) {
			throw new BusinessException(ErrorCode.MARKET_CLOSED);
		}
		PriceQuoteDto priceQuote = stockQuote.isPriceAvailable()
			? new PriceQuoteDto(
				stockQuote.price(), stockQuote.sourceTime(), PriceStatus.AVAILABLE,
				stockQuote.sourceTradingDate())
			: new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, stockQuote.sourceTradingDate());
		return new OrderExecutionPriceDto(requireAvailable(priceQuote), stockQuote.replaySession());
	}

	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Long instrumentId) {
		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return getPriceQuote(instrument);
	}

	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Instrument instrument) {
		if (instrument.isTutorialSample()) {
			return tutorialSampleInstrumentPriceService.getPriceQuote(instrument);
		}
		return instrument.getMarket() == Market.STOCK ? getStockPriceQuote(instrument)
			: getCryptoDisplayPriceQuote(instrument);
	}

	@Transactional(readOnly = true)
	public List<PriceQuoteDto> getPriceQuotes(List<Instrument> instruments) {
		if (instruments.isEmpty()) {
			return List.of();
		}
		List<Instrument> realInstruments = instruments.stream().filter(instrument -> !instrument.isTutorialSample())
			.toList();
		Map<Instrument, PriceQuoteDto> realQuotesByInstrument = new IdentityHashMap<>();
		if (!realInstruments.isEmpty()) {
			Market market = realInstruments.get(0).getMarket();
			if (realInstruments.stream().anyMatch(instrument -> instrument.getMarket() != market)) {
				throw new IllegalArgumentException("getPriceQuotes는 서로 다른 market이 섞인 종목 목록을 받을 수 없습니다.");
			}
			List<PriceQuoteDto> realQuotes = market == Market.STOCK ? getStockPriceQuotes(realInstruments)
				: getCryptoDisplayPriceQuotes(realInstruments);
			for (int i = 0; i < realInstruments.size(); i++) {
				realQuotesByInstrument.put(realInstruments.get(i), realQuotes.get(i));
			}
		}
		return instruments.stream()
			.map(instrument -> instrument.isTutorialSample()
				? tutorialSampleInstrumentPriceService.getPriceQuote(instrument)
				: realQuotesByInstrument.get(instrument))
			.toList();
	}

	private List<PriceQuoteDto> getStockPriceQuotes(List<Instrument> instruments) {
		List<Long> instrumentIds = instruments.stream().map(Instrument::getId).toList();
		List<StockReplayPriceDto> quotes = stockPriceProvider.getCurrentPrices(instrumentIds);
		return quotes.stream()
			.map(quote -> quote.isPriceAvailable()
				? new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate())
				: new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, quote.sourceTradingDate()))
			.toList();
	}

	private List<PriceQuoteDto> getCryptoDisplayPriceQuotes(List<Instrument> instruments) {
		if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return instruments.stream().map(instrument -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null))
				.toList();
		}
		return instruments.stream()
			.map(instrument -> priceStore.getLatestPrice(instrument.getSymbol())
				.map(price -> new PriceQuoteDto(price.price(), price.receivedAt(), PriceStatus.AVAILABLE, null))
				.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)))
			.toList();
	}

	private PriceQuoteDto requireAvailable(PriceQuoteDto quote) {
		if (quote.status() == PriceStatus.UNAVAILABLE) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		return quote;
	}

	private PriceQuoteDto getStockPriceQuote(Instrument instrument) {
		StockReplayPriceDto quote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (!quote.isPriceAvailable()) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, quote.sourceTradingDate());
		}
		return new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate());
	}

	private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) {
		if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
		}
		return priceStore.getLatestPrice(instrument.getSymbol())
			.map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.AVAILABLE, null))
			.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
	}
}
