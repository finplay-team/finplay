package com.finplay.api.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TutorialAccountServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);

	@Test
	void getOrCreateForUpdateReturnsExistingAccountWithoutCreatingOne() {
		TutorialAccountRepository tutorialAccountRepository = mock(TutorialAccountRepository.class);
		UserQueryService userQueryService = mock(UserQueryService.class);
		TutorialAccountService tutorialAccountService = new TutorialAccountService(tutorialAccountRepository,
			userQueryService);
		TutorialAccount existing = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		when(tutorialAccountRepository.findByUserIdAndMarketForUpdate(1L, Market.STOCK))
			.thenReturn(Optional.of(existing));

		TutorialAccount result = tutorialAccountService.getOrCreateForUpdate(1L, Market.STOCK, NOW);

		assertThat(result).isSameAs(existing);
		verify(userQueryService, never()).getUser(any());
		verify(tutorialAccountRepository, never()).save(any());
	}

	@Test
	void getOrCreateForUpdateCreatesAccountWithInitialBalancesWhenNoneExists() {
		TutorialAccountRepository tutorialAccountRepository = mock(TutorialAccountRepository.class);
		UserQueryService userQueryService = mock(UserQueryService.class);
		TutorialAccountService tutorialAccountService = new TutorialAccountService(tutorialAccountRepository,
			userQueryService);
		User user = testUser();
		when(userQueryService.getUser(1L)).thenReturn(user);
		TutorialAccount reloaded = TutorialAccount.create(user, Market.CRYPTO, NOW);
		when(tutorialAccountRepository.findByUserIdAndMarketForUpdate(1L, Market.CRYPTO))
			.thenReturn(Optional.empty(), Optional.of(reloaded));

		TutorialAccount result = tutorialAccountService.getOrCreateForUpdate(1L, Market.CRYPTO, NOW);

		assertThat(result).isSameAs(reloaded);
		ArgumentCaptor<TutorialAccount> savedCaptor = ArgumentCaptor.forClass(TutorialAccount.class);
		verify(tutorialAccountRepository).save(savedCaptor.capture());
		TutorialAccount saved = savedCaptor.getValue();
		assertThat(saved.getUser()).isSameAs(user);
		assertThat(saved.getMarket()).isEqualTo(Market.CRYPTO);
		assertThat(saved.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(saved.getReservedCash()).isZero();
		assertThat(saved.getRealizedPnl()).isZero();
	}

	@Test
	void getOrCreateForUpdateThrowsIllegalStateWhenReloadAfterCreateFindsNothing() {
		TutorialAccountRepository tutorialAccountRepository = mock(TutorialAccountRepository.class);
		UserQueryService userQueryService = mock(UserQueryService.class);
		TutorialAccountService tutorialAccountService = new TutorialAccountService(tutorialAccountRepository,
			userQueryService);
		when(userQueryService.getUser(1L)).thenReturn(testUser());
		when(tutorialAccountRepository.findByUserIdAndMarketForUpdate(1L, Market.STOCK))
			.thenReturn(Optional.empty(), Optional.empty());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> tutorialAccountService.getOrCreateForUpdate(1L, Market.STOCK, NOW))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void resetForUpdateRestoresCashReservedCashAndRealizedPnlOnExistingAccount() {
		TutorialAccountRepository tutorialAccountRepository = mock(TutorialAccountRepository.class);
		UserQueryService userQueryService = mock(UserQueryService.class);
		TutorialAccountService tutorialAccountService = new TutorialAccountService(tutorialAccountRepository,
			userQueryService);
		TutorialAccount existing = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		existing.deductCash(9_000_000L);
		existing.reserveCash(500_000L);
		existing.addRealizedPnl(-300_000L);
		when(tutorialAccountRepository.findByUserIdAndMarketForUpdate(1L, Market.STOCK))
			.thenReturn(Optional.of(existing));
		LocalDateTime restartedAt = NOW.plusDays(1);

		tutorialAccountService.resetForUpdate(1L, Market.STOCK, restartedAt);

		assertThat(existing.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(existing.getReservedCash()).isZero();
		assertThat(existing.getRealizedPnl()).isZero();
		assertThat(existing.getUpdatedAt()).isEqualTo(restartedAt);
	}

	@Test
	void resetForUpdateCreatesAccountWithInitialBalancesWhenNoneExisted() {
		TutorialAccountRepository tutorialAccountRepository = mock(TutorialAccountRepository.class);
		UserQueryService userQueryService = mock(UserQueryService.class);
		TutorialAccountService tutorialAccountService = new TutorialAccountService(tutorialAccountRepository,
			userQueryService);
		User user = testUser();
		when(userQueryService.getUser(1L)).thenReturn(user);
		TutorialAccount reloaded = TutorialAccount.create(user, Market.CRYPTO, NOW);
		when(tutorialAccountRepository.findByUserIdAndMarketForUpdate(1L, Market.CRYPTO))
			.thenReturn(Optional.empty(), Optional.of(reloaded));

		tutorialAccountService.resetForUpdate(1L, Market.CRYPTO, NOW);

		assertThat(reloaded.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(reloaded.getReservedCash()).isZero();
		assertThat(reloaded.getRealizedPnl()).isZero();
	}

	private static User testUser() {
		return User.create("tutorial-trader@finplay.com", "password-hash", "tutorial-trader", NOW);
	}
}
