// Account의 현금 차감 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
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

	@Test
	void addCashIncreasesCashBalanceByAmount() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		account.addCash(500_000L);

		assertThat(account.getCashBalance()).isEqualTo(10_500_000L);
	}

	@Test
	void addRealizedPnlIncreasesRealizedPnlWithPositiveAmount() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		account.addRealizedPnl(49_900L);

		assertThat(account.getRealizedPnl()).isEqualTo(49_900L);
	}

	@Test
	void addRealizedPnlAllowsNegativeAmountToAccumulateLoss() {
		Account account = Account.create(testUser(), Market.STOCK, NOW);

		account.addRealizedPnl(-20_000L);

		assertThat(account.getRealizedPnl()).isEqualTo(-20_000L);
	}

	@Test
	void getAvailableCashReturnsCashBalanceMinusReservedCash() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);

		account.reserveCash(3_000_000L);

		assertThat(account.getAvailableCash()).isEqualTo(7_000_000L);
	}

	@Test
	void reserveCashIncreasesReservedCashWithoutChangingCashBalance() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);

		account.reserveCash(2_000_000L);

		assertThat(account.getReservedCash()).isEqualTo(2_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void reserveCashThrowsIllegalStateExceptionWhenAmountExceedsAvailableCash() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);

		assertThatThrownBy(() -> account.reserveCash(10_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isZero();
	}

	@Test
	void reserveCashThrowsIllegalStateExceptionWhenExceedingAlreadyReservedAvailableCash() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(9_000_000L);

		assertThatThrownBy(() -> account.reserveCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(9_000_000L);
	}

	@Test
	void confirmReservedCashDecreasesBothReservedCashAndCashBalance() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(3_000_000L);

		account.confirmReservedCash(3_000_000L);

		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(7_000_000L);
	}

	@Test
	void confirmReservedCashThrowsIllegalStateExceptionWhenAmountExceedsReservedCash() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(1_000_000L);

		assertThatThrownBy(() -> account.confirmReservedCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(1_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void releaseReservedCashDecreasesReservedCashWithoutChangingCashBalance() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(3_000_000L);

		account.releaseReservedCash(3_000_000L);

		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void releaseReservedCashThrowsIllegalStateExceptionWhenAmountExceedsReservedCash() {
		Account account = Account.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(1_000_000L);

		assertThatThrownBy(() -> account.releaseReservedCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(1_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
