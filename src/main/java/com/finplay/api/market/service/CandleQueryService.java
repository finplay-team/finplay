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
		// CandleInterval.from은 값을 쓰지 않고 "1m 외에는 즉시 400"이라는 검증 목적으로만 호출한다(반환값은 의도적으로 버림).
		CandleInterval.from(interval);
		// 리뷰 확정(PR #87 QA FAIL, 옵션 c): 캔들은 항상 재생 중인 단일 거래일(source_trading_date) 안에서만 조회되므로
		// from·to의 날짜 성분은 무시하고 시각(LocalTime)만 쓴다. from>to 판정도 같은 기준(시각)으로 통일한다 —
		// 그렇지 않으면 날짜 기준 판정과 실제 조회에 쓰이는 시각 기준 필터링이 서로 어긋나 검증을 통과한 요청이
		// 조용히 빈 배열을 반환하거나, 시각상 유효한 요청이 날짜 때문에 400으로 거부되는 문제가 있었다.
		if (from != null && to != null && from.toLocalTime().isAfter(to.toLocalTime())) {
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
