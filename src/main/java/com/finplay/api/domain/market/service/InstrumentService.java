package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.dto.response.InstrumentResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
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
	public Instrument getInstrumentEntity(Long instrumentId) {
		return instrumentRepository.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	@Transactional(readOnly = true)
	public Instrument getTradableInstrumentEntity(Long instrumentId) {
		Instrument instrument = instrumentRepository.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(
				ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));
		if (!instrument.isTradable() || instrument.isTutorialSample()) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다.");
		}
		return instrument;
	}

	@Transactional(readOnly = true)
	public List<Instrument> getInstrumentEntities(Market market) {
		return instrumentRepository.findByMarketOrderByIdAsc(market);
	}

	@Transactional(readOnly = true)
	public List<Instrument> getRealInstrumentEntities(Market market) {
		return instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(market);
	}

	@Transactional(readOnly = true)
	public Optional<Instrument> findEntityByMarketAndSymbol(Market market, String symbol) {
		return instrumentRepository.findByMarketAndSymbol(market, symbol);
	}
}
