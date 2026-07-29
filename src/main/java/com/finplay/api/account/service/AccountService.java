// 회원가입 시 STOCK·CRYPTO 초기 계좌를 같은 시각에 생성하는 서비스
package com.finplay.api.account.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.service.HoldingValuationDto;
import com.finplay.api.portfolio.service.HoldingValuationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

	private static final int RETURN_RATE_SCALE = 4;

	private final AccountRepository accountRepository;
	private final HoldingValuationService holdingValuationService;
	private final Clock clock;

	@Transactional
	public void createAccountsFor(User user) {
		LocalDateTime now = LocalDateTime.now(clock);
		accountRepository.saveAll(List.of(
			Account.create(user, Market.STOCK, now),
			Account.create(user, Market.CRYPTO, now)));
	}

	@Transactional(readOnly = true)
	public Account getAccountFor(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarket(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	@Transactional(readOnly = true)
	public AccountSummaryResponse getAccountSummary(Long userId, Market market) {
		Account account = getAccountFor(userId, market);

		List<HoldingValuationDto> valuations = holdingValuationService
			.evaluateActiveHoldingsForAccount(account.getId());
		long holdingsValue = 0L;
		long unrealizedPnl = 0L;
		for (HoldingValuationDto valuation : valuations) {
			if (valuation.priceStatus() == PriceStatus.AVAILABLE) {
				holdingsValue += valuation.evaluationAmount();
				unrealizedPnl += valuation.unrealizedPnl();
			} else {
				// 시세 무효(휴장 등)는 손익을 모르니 원가만큼 있는 것으로 취급 — 미실현손익은 0 기여
				holdingsValue += valuation.costBasis();
			}
		}

		long cashBalance = account.getCashBalance();
		long totalValue = cashBalance + holdingsValue;
		long realizedPnl = account.getRealizedPnl();
		long seedMoney = account.getSeedMoney();
		BigDecimal returnRate = seedMoney == 0
			? BigDecimal.ZERO
			: BigDecimal.valueOf(totalValue - seedMoney)
				.divide(BigDecimal.valueOf(seedMoney), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		return AccountSummaryResponse.of(cashBalance, holdingsValue, totalValue, realizedPnl, unrealizedPnl,
			returnRate);
	}
}
