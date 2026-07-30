// 시장별 보유 종목 목록 조회를 담당하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.util.List;
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
}
