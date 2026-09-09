package com.finplay.api.domain.account.service;

import com.finplay.api.domain.account.dto.response.AccountSummaryResponse;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.portfolio.service.HoldingValuationDto;
import com.finplay.api.domain.portfolio.service.HoldingValuationService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

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
	public Account getAccountForWithUser(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarketFetchUser(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	@Transactional
	public Account getAccountForUpdate(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	@Transactional
	public Account getAccountByIdForUpdate(Long accountId) {
		return accountRepository
			.findByIdForUpdate(accountId)
			.orElseThrow(() -> new IllegalStateException("체결 대상 계좌를 찾을 수 없습니다. accountId=" + accountId));
	}

	@Transactional
	public List<Account> getAccountsByIdsForUpdate(List<Long> accountIds) {
		return accountRepository.findByIdInForUpdate(accountIds);
	}

	@Transactional(readOnly = true)
	public Optional<Account> findByIdOrEmpty(Long accountId) {
		return accountRepository.findById(accountId);
	}

	@Transactional(readOnly = true)
	public List<Account> getAccountsWithUser(List<Long> accountIds) {
		return accountRepository.findAllByIdInFetchUser(accountIds);
	}

	@Transactional(readOnly = true)
	public List<Account> getAccountsByIds(List<Long> accountIds) {
		return accountRepository.findAllById(accountIds);
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
				holdingsValue += valuation.costBasis();
			}
		}

		long cashBalance = account.getCashBalance();
		long totalValue = cashBalance + holdingsValue;
		long realizedPnl = account.getRealizedPnl();

		return AccountSummaryResponse.of(cashBalance, account.getReservedCash(), holdingsValue, totalValue,
			realizedPnl, unrealizedPnl);
	}
}
