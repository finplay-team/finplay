// 시장별 보유 종목 목록 조회를 담당하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldingService {

	private final AccountService accountService;
	private final HoldingRepository holdingRepository;
	private final HoldingValuationService holdingValuationService;

	@Transactional(readOnly = true)
	public List<HoldingListItemResponse> getHoldings(Long userId, Market market) {
		Account account = accountService.getAccountFor(userId, market);
		// 계좌(=market) 단위 배치 경로를 사용해 종목과 무관한 전역 상태 조회를 요청당 1회로 줄인다 (PR #97 리뷰 권장사항).
		List<Holding> holdings = holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId());
		List<HoldingValuationDto> valuations = holdingValuationService.evaluateHoldings(holdings);
		return IntStream.range(0, holdings.size())
			.mapToObj(i -> HoldingListItemResponse.of(holdings.get(i), valuations.get(i)))
			.toList();
	}

	// 026-market-order-practice-tutorial 2단계 chain 해석용 — owner·instrument 일치 holding의 id만 반환한다
	// (현재 수량은 검증하지 않는다, spec.md "2단계 완료 증거"). education 도메인이 HoldingRepository를 직접
	// 주입하지 않도록 이 서비스 메서드만 거치게 한다(ADR-0002). market은 종목의 market(market.domain.Market)을
	// 받아 계좌 조회에 필요한 account.domain.Market으로 내부 변환한다(OrderExecutionService의 기존 변환 관례).
	@Transactional(readOnly = true)
	public Optional<Long> findHoldingId(
		Long userId, com.finplay.api.market.domain.Market market, Long instrumentId) {
		Market accountMarket = Market.valueOf(market.name());
		Account account = accountService.getAccountFor(userId, accountMarket);
		return holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrumentId).map(Holding::getId);
	}

	// 041 tick 진행 계산용 — 대기 구간 탈출 판정에 쓰는 순보유수량을 가상 분마다 다시 읽는다. education이
	// HoldingRepository를 직접 주입하지 않도록 이 서비스만 거치게 한다(ADR-0002, findHoldingId와 같은 관례).
	@Transactional(readOnly = true)
	public java.math.BigDecimal findNetQuantity(
		Long userId, com.finplay.api.market.domain.Market market, Long instrumentId) {
		return holdingRepository
			.findQuantityByOwnerAndInstrument(userId, Market.valueOf(market.name()), instrumentId)
			.orElse(java.math.BigDecimal.ZERO);
	}

	// 026-market-order-practice-tutorial 3단계 관찰 API용 — holdingId로 조회하되 계좌 소유자가 본인이 아니면
	// 존재를 숨겨 빈 값을 반환한다(호출측이 404 NOT_FOUND로 매핑).
	// 021 PR #368 리뷰 차단 1: instrument는 LAZY라 open-in-view=false 환경에서 이 메서드가 반환한 뒤(트랜잭션
	// 종료 후) 호출부가 holding.getInstrument()에 접근하면 LazyInitializationException이 난다. account는
	// 아래 filter에서 이미 이 세션 안에 초기화되므로 안전하지만, instrument는 그렇지 않아 JOIN FETCH로 즉시
	// 로딩한다(findByIdFetchingInstrument).
	@Transactional(readOnly = true)
	public Optional<Holding> findHoldingForOwner(Long userId, Long holdingId) {
		return holdingRepository.findByIdFetchingInstrument(holdingId)
			.filter(holding -> holding.getAccount().getUser().getId().equals(userId));
	}
}
