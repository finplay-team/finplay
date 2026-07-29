// 주문·화면·평가손익이 공통으로 소비하는 "유효한 최신 가격" 조회 서비스. 주식은 주입된 StockPriceProvider(구현체 불명)에, 코인은 PriceStore(Redis)에 위임한다.
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.PriceStore;
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

	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Instrument instrument) {
		return requireAvailable(getPriceQuote(instrument));
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

	private PriceQuoteDto requireAvailable(PriceQuoteDto quote) {
		if (quote.status() == PriceStatus.UNAVAILABLE) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		return quote;
	}

	// 주식 장외는 MARKET_CLOSED, 그 외(코인·주식 장중)는 통과시킨다. 주문 서비스는 이 메서드로만 주문 가능 여부를 판단해야 한다.
	@Transactional(readOnly = true)
	public void assertOrderable(Instrument instrument) {
		if (instrument.getMarket() == Market.STOCK
			&& stockPriceProvider.getMarketStatus() == StockMarketStatus.CLOSED) {
			throw new BusinessException(ErrorCode.MARKET_CLOSED);
		}
	}

	// 어느 StockPriceProvider 구현체(MVP는 KrxReplayPriceProvider 하나뿐)가 동작 중인지 알지 못한 채 인터페이스로만 위임한다.
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
