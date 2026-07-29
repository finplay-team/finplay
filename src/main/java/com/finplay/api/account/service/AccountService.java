// 회원가입 시 STOCK·CRYPTO 초기 계좌를 같은 시각에 생성하는 서비스
package com.finplay.api.account.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

	private final AccountRepository accountRepository;
	private final Clock clock;

	public AccountService(AccountRepository accountRepository, Clock clock) {
		this.accountRepository = accountRepository;
		this.clock = clock;
	}

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
}
