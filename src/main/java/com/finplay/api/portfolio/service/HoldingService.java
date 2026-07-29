// 시장별 보유 종목 목록 조회를 담당하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.util.List;
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
		return holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId()).stream()
			.map(holding -> HoldingListItemResponse.from(holding, holdingValuationService.evaluateHolding(holding)))
			.toList();
	}
}
