// 주문·화면·평가손익이 공통으로 소비하는 "유효한 최신 가격" 조회 서비스. 주식은 주입된 StockPriceProvider(구현체 불명)에, 코인은 PriceStore(Redis)에 위임한다.
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.CryptoPriceDto;
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

	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Long instrumentId) {
		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return getPrice(instrument);
	}

	@Transactional(readOnly = true)
	public PriceQuoteDto getPrice(Instrument instrument) {
		return instrument.getMarket() == Market.STOCK ? getStockPrice(instrument) : getCryptoPrice(instrument);
	}

	// 어느 StockPriceProvider 구현체(KrxReplayPriceProvider·KisRealtimePriceProvider)가 동작 중인지 알지 못한 채 인터페이스로만 위임한다.
	private PriceQuoteDto getStockPrice(Instrument instrument) {
		StockReplayPriceDto quote = stockPriceProvider.getCurrentPrice(instrument.getId());
		if (!quote.isPriceAvailable()) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		return new PriceQuoteDto(quote.price(), quote.sourceTime(), PriceStatus.AVAILABLE, quote.sourceTradingDate());
	}

	private PriceQuoteDto getCryptoPrice(Instrument instrument) {
		String symbol = instrument.getSymbol();
		if (!priceStore.isPriceAvailable(symbol)) {
			throw new BusinessException(ErrorCode.PRICE_UNAVAILABLE);
		}
		CryptoPriceDto latestPrice = priceStore
			.getLatestPrice(symbol)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
		return new PriceQuoteDto(latestPrice.price(), latestPrice.receivedAt(), PriceStatus.AVAILABLE, null);
	}
}
