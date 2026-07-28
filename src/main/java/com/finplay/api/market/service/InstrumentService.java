// 시장 필터를 적용해 종목 목록을 조회하는 서비스
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InstrumentService {

	private final InstrumentRepository instrumentRepository;

	@Transactional(readOnly = true)
	public List<InstrumentResponse> getInstruments(Market market) {
		List<Instrument> instruments = market == null
			? instrumentRepository.findAllByOrderByIdAsc()
			: instrumentRepository.findByMarketOrderByIdAsc(market);
		return instruments.stream()
			.map(InstrumentResponse::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public InstrumentResponse getInstrument(Long instrumentId) {
		Instrument instrument = instrumentRepository.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return InstrumentResponse.from(instrument);
	}
}
