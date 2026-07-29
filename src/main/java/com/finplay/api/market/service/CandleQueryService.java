// 주식 종목의 1분봉 캔들 조회 요청을 검증하고 StockPriceProvider에 위임하는 서비스
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandleQueryService {

	private final InstrumentRepository instrumentRepository;
	private final StockPriceProvider stockPriceProvider;

	@Transactional(readOnly = true)
	public List<CandleResponse> getCandles(Long instrumentId, String interval, LocalDateTime from, LocalDateTime to) {
		CandleInterval.from(interval);
		if (from != null && to != null && from.isAfter(to)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다.");
		}

		Instrument instrument = instrumentRepository
			.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (instrument.getMarket() != Market.STOCK) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "주식 종목만 캔들 조회를 지원합니다.");
		}

		return stockPriceProvider.getCandles(instrumentId, from, to).stream()
			.map(CandleResponse::from)
			.toList();
	}
}
