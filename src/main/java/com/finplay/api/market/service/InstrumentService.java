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
		return InstrumentResponse.from(getInstrumentEntity(instrumentId));
	}

	// 다른 도메인(order 등)이 Instrument 엔티티가 필요할 때 InstrumentRepository를 직접 주입하지 않고 이 메서드만 거치게 한다 (ADR-0002).
	@Transactional(readOnly = true)
	public Instrument getInstrumentEntity(Long instrumentId) {
		return instrumentRepository.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 위와 같은 이유로 시장 단위 순회도 이 메서드를 거친다 — feedback의 뉴스·공시 수집이 종목 목록을 얻는 경로다
	// (spec 012 §C-6 "feedback은 다른 도메인의 repository·store를 직접 주입하지 않는다").
	// getInstruments와 달리 응답 DTO가 아니라 엔티티를 주는 이유는 호출부가 MarketNewsItem의 연관으로 그대로
	// 써야 하기 때문이다 — DTO로는 ManyToOne을 채울 수 없다.
	@Transactional(readOnly = true)
	public List<Instrument> getInstrumentEntities(Market market) {
		return instrumentRepository.findByMarketOrderByIdAsc(market);
	}
}
