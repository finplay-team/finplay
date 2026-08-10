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

	// 026-market-order-practice-tutorial 3단계 관찰 API용 — holdingId로 조회하되 계좌 소유자가 본인이 아니면
	// 존재를 숨겨 빈 값을 반환한다(호출측이 404 NOT_FOUND로 매핑).
	@Transactional(readOnly = true)
	public Optional<Holding> findHoldingForOwner(Long userId, Long holdingId) {
		return holdingRepository.findById(holdingId)
			.filter(holding -> holding.getAccount().getUser().getId().equals(userId));
	}
}
