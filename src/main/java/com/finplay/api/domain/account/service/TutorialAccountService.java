package com.finplay.api.domain.account.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TutorialAccountService {

	private final TutorialAccountRepository tutorialAccountRepository;
	private final UserQueryService userQueryService;

	@Transactional
	public TutorialAccount getOrCreateForUpdate(Long userId, Market market, LocalDateTime now) {
		return tutorialAccountRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseGet(() -> createAndReload(userId, market, now));
	}

	private TutorialAccount createAndReload(Long userId, Market market, LocalDateTime now) {
		User user = userQueryService.getUser(userId);
		tutorialAccountRepository.save(TutorialAccount.create(user, market, now));
		return tutorialAccountRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new IllegalStateException(
				"생성 직후 튜토리얼 계좌를 조회하지 못했습니다. userId=" + userId + ", market=" + market));
	}

	@Transactional(readOnly = true)
	public Optional<TutorialAccount> find(Long userId, Market market) {
		return tutorialAccountRepository.findByUserIdAndMarket(userId, market);
	}

	@Transactional
	public void resetForUpdate(Long userId, Market market, LocalDateTime now) {
		TutorialAccount account = getOrCreateForUpdate(userId, market, now);
		account.reset(now);
	}
}
