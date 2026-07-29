// Account의 현금 차감 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.auth.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AccountTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void deductCashReducesCashBalanceByAmount() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		account.deductCash(3_000_000L);

		assertThat(account.getCashBalance()).isEqualTo(7_000_000L);
	}

	@Test
	void deductCashAllowsDeductingExactCashBalanceLeavingZero() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		account.deductCash(10_000_000L);

		assertThat(account.getCashBalance()).isZero();
	}

	@Test
	void deductCashThrowsIllegalStateExceptionWhenAmountExceedsCashBalance() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		assertThatThrownBy(() -> account.deductCash(10_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
