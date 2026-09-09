package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.CandleResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandleQueryService {

	private static final int PAGE_SIZE = 200;

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;
	private final CryptoCandleProvider cryptoCandleProvider;

	@Transactional(readOnly = true)
	public CandleListResponse getCandles(
		Long instrumentId, String interval, LocalDateTime from, LocalDateTime to, String cursor) {
		CandleInterval candleInterval = CandleInterval.from(interval);

		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		boolean isCrypto = instrument.getMarket() == Market.CRYPTO;
		boolean isStockOneMinute = !isCrypto && candleInterval == CandleInterval.ONE_MINUTE;

		LocalDateTime parsedCursor = CandleCursor.parse(cursor);

		boolean cursorApplies = parsedCursor != null && !isStockOneMinute;

		LocalDateTime effectiveTo = to;
		boolean lowerBoundReversed = false;

		if (isCrypto) {
			if (cursorApplies) {
				effectiveTo = parsedCursor.minusMinutes(1);
				lowerBoundReversed = from != null && from.isAfter(effectiveTo);
			} else if (from != null && to != null && from.isAfter(to)) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		} else if (candleInterval.isAggregated()) {
			if (cursorApplies) {
				effectiveTo = parsedCursor.minusMinutes(1);
				lowerBoundReversed = from != null && from.toLocalDate().isAfter(effectiveTo.toLocalDate());
			} else if (from != null && to != null && from.toLocalDate().isAfter(to.toLocalDate())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		} else {
			if (from != null && to != null && from.toLocalTime().isAfter(to.toLocalTime())) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
			}
		}

		if (cursorApplies && lowerBoundReversed) {
			return CandleListResponse.of(List.of(), null, false);
		}

		List<CandleResponse> content;
		if (isCrypto) {
			content = cryptoCandleProvider.getCandles(instrument.getSymbol(), candleInterval, from, effectiveTo)
				.stream()
				.map(CandleResponse::from)
				.toList();
		} else {
			content = stockPriceProvider.getCandles(instrumentId, candleInterval, from, effectiveTo).stream()
				.map(CandleResponse::from)
				.toList();
		}

		boolean hasNext = !isStockOneMinute && content.size() == PAGE_SIZE;
		String nextCursor = hasNext ? CandleCursor.encode(content.get(0).sourceTime()) : null;

		return CandleListResponse.of(content, nextCursor, hasNext);
	}

	public List<CryptoCandleDto> getCryptoCandles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		return cryptoCandleProvider.getCandles(symbol, interval, from, to);
	}
}
