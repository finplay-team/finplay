// 시장 필터를 적용해 종목 목록을 조회하는 서비스
package com.finplay.api.market.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.repository.InstrumentRepository;
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
	public InstrumentResponse getInstrument(Long instrumentId) {
		return InstrumentResponse.from(getInstrumentEntity(instrumentId));
	}

	// 다른 도메인(order 등)이 Instrument 엔티티가 필요할 때 InstrumentRepository를 직접 주입하지 않고 이 메서드만 거치게 한다 (ADR-0002).
	@Transactional(readOnly = true)
	public Instrument getInstrumentEntity(Long instrumentId) {
		return instrumentRepository.findById(instrumentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 커뮤니티 게시물 종목 태그(COM-004)처럼 "존재하지 않거나 비활성"을 400 VALIDATION_ERROR로
	// 다뤄야 하는 도메인을 위한 조회. getInstrumentEntity(404 NOT_FOUND)와 별도로 둔다 — 두 그룹의
	// 오류 계약이 다르기 때문(예: 지정가 주문은 종목 없음을 404로 다룬다).
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

	// 위와 같은 이유로 시장 단위 순회도 이 메서드를 거친다 — feedback의 뉴스·공시 수집이 종목 목록을 얻는 경로다
	// (spec 012 §C-6 "feedback은 다른 도메인의 repository·store를 직접 주입하지 않는다").
	// getInstruments와 달리 응답 DTO가 아니라 엔티티를 주는 이유는 호출부가 MarketNewsItem의 연관으로 그대로
	// 써야 하기 때문이다 — DTO로는 ManyToOne을 채울 수 없다.
	@Transactional(readOnly = true)
	public List<Instrument> getInstrumentEntities(Market market) {
		return instrumentRepository.findByMarketOrderByIdAsc(market);
	}

	// 지정가 체결 리스너(order 도메인)가 가격 갱신 이벤트의 심볼로 종목을 조회할 때 이 메서드만 거치게 한다
	// (ADR-0002, 015-limit-order LMT-002). 목록에 없는 심볼일 수 있으므로 예외 대신 빈 Optional로 관용 처리한다.
	@Transactional(readOnly = true)
	public Optional<Instrument> findEntityByMarketAndSymbol(Market market, String symbol) {
		return instrumentRepository.findByMarketAndSymbol(market, symbol);
	}
}
