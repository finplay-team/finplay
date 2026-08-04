// 주문·화면·평가손익이 공통으로 소비하는 "유효한 최신 가격" 조회 서비스. 주식은 주입된 StockPriceProvider(구현체 불명)에, 코인은 PriceStore(Redis)에 위임한다.
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.CryptoPriceDto;
import com.finplay.api.market.store.PriceStore;
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

	// 가격이 없으면 PRICE_UNAVAILABLE 예외를 던진다 — 가격 API의 409 계약 (이슈 #16 확정, 동작 변경 없음). getPriceQuote를 감싸 판정 로직을 중복하지 않는다.
	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Long instrumentId) {
		return requireAvailable(getPriceQuote(instrumentId));
	}

	// 주문 체결 전용 — 주식의 주문 가능 상태·가격·재생세션을 공급자의 같은 관측 결과로 확정한다.
	@Transactional(readOnly = true)
	public OrderExecutionPriceDto getOrderExecutionPrice(Instrument instrument) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return new OrderExecutionPriceDto(requireAvailable(getCryptoPriceQuote(instrument)), null);
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

	// 가격이 없어도 예외를 던지지 않고 status=UNAVAILABLE로 표현한다 — SSE snapshot·price처럼 "가격 없음"도 정상 응답인 소비자를 위한 경로 (이슈 #18).
	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Long instrumentId) {
		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return getPriceQuote(instrument);
	}

	@Transactional(readOnly = true)
	public PriceQuoteDto getPriceQuote(Instrument instrument) {
		return instrument.getMarket() == Market.STOCK ? getStockPriceQuote(instrument)
			: getCryptoPriceQuote(instrument);
	}

	// 계좌(=market) 단위로 여러 종목의 시세를 한 번에 조회한다 — 호출측(HoldingValuationService 등)이 이미 계좌 단위로 종목을
	// 모아서 넘기므로 instruments는 전부 같은 market이라고 가정한다 (PR #97 리뷰 권장사항, 다음 이슈 #51 착수 전 정리).
	// 반환 순서는 instruments 순서와 일치한다. 이 가정이 깨지면(market 혼재) 호출측 버그이므로 방어적으로 예외를 던진다
	// (#51이 계좌 두 개를 다루기 시작하면 실수로 섞어 넘길 위험이 커지므로 지금 막아둔다).
	@Transactional(readOnly = true)
	public List<PriceQuoteDto> getPriceQuotes(List<Instrument> instruments) {
		if (instruments.isEmpty()) {
			return List.of();
		}
		Market market = instruments.get(0).getMarket();
		if (instruments.stream().anyMatch(instrument -> instrument.getMarket() != market)) {
			throw new IllegalArgumentException("getPriceQuotes는 서로 다른 market이 섞인 종목 목록을 받을 수 없습니다.");
		}
		return market == Market.STOCK ? getStockPriceQuotes(instruments) : getCryptoPriceQuotes(instruments);
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

	private List<PriceQuoteDto> getCryptoPriceQuotes(List<Instrument> instruments) {
		List<String> symbols = instruments.stream().map(Instrument::getSymbol).toList();
		Map<String, CryptoPriceDto> latestPrices = priceStore.getLatestPrices(symbols);
		return instruments.stream()
			.map(instrument -> {
				CryptoPriceDto price = latestPrices.get(instrument.getSymbol());
				return price == null ? new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)
					: new PriceQuoteDto(price.price(), price.receivedAt(), PriceStatus.AVAILABLE, null);
			})
			.toList();
	}

	private PriceQuoteDto requireAvailable(PriceQuoteDto quote) {
		if (quote.status() == PriceStatus.UNAVAILABLE) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		return quote;
	}

	// 어느 StockPriceProvider 구현체(MVP는 KisHistoricalReplayPriceProvider 하나뿐)가 동작 중인지 알지 못한 채 인터페이스로만 위임한다.
	private PriceQuoteDto getStockPriceQuote(Instrument instrument) {
		StockReplayPriceDto quote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (!quote.isPriceAvailable()) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, quote.sourceTradingDate());
		}
		return new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate());
	}

	private PriceQuoteDto getCryptoPriceQuote(Instrument instrument) {
		String symbol = instrument.getSymbol();
		if (!priceStore.isPriceAvailable(symbol)) {
			return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null);
		}
		return priceStore
			.getLatestPrice(symbol)
			.map(latestPrice -> new PriceQuoteDto(latestPrice.price(), latestPrice.receivedAt(), PriceStatus.AVAILABLE,
				null))
			.orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
	}
}
