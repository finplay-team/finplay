// TutorialAccount의 현금·예약·실현손익 증감과 재시작 리셋을 검증하는 순수 단위 테스트다.
package com.finplay.api.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.auth.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TutorialAccountTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);

	@Test
	void createInitializesCashBalanceToTenMillionAndOthersToZero() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getRealizedPnl()).isZero();
		assertThat(account.getMarket()).isEqualTo(Market.STOCK);
	}

	@Test
	void deductCashReducesCashBalanceByAmount() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		account.deductCash(3_000_000L);

		assertThat(account.getCashBalance()).isEqualTo(7_000_000L);
	}

	@Test
	void deductCashAllowsDeductingExactCashBalanceLeavingZero() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		account.deductCash(10_000_000L);

		assertThat(account.getCashBalance()).isZero();
	}

	@Test
	void deductCashThrowsIllegalStateExceptionWhenAmountExceedsCashBalance() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		assertThatThrownBy(() -> account.deductCash(10_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void addCashIncreasesCashBalanceByAmount() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		account.addCash(500_000L);

		assertThat(account.getCashBalance()).isEqualTo(10_500_000L);
	}

	@Test
	void getAvailableCashReturnsCashBalanceMinusReservedCash() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);

		account.reserveCash(3_000_000L);

		assertThat(account.getAvailableCash()).isEqualTo(7_000_000L);
	}

	@Test
	void reserveCashIncreasesReservedCashWithoutChangingCashBalance() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);

		account.reserveCash(2_000_000L);

		assertThat(account.getReservedCash()).isEqualTo(2_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void reserveCashThrowsIllegalStateExceptionWhenAmountExceedsAvailableCash() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);

		assertThatThrownBy(() -> account.reserveCash(10_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isZero();
	}

	@Test
	void reserveCashThrowsIllegalStateExceptionWhenExceedingAlreadyReservedAvailableCash() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(9_000_000L);

		assertThatThrownBy(() -> account.reserveCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(9_000_000L);
	}

	@Test
	void confirmReservedCashDecreasesBothReservedCashAndCashBalance() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(3_000_000L);

		account.confirmReservedCash(3_000_000L);

		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(7_000_000L);
	}

	@Test
	void confirmReservedCashThrowsIllegalStateExceptionWhenAmountExceedsReservedCash() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(1_000_000L);

		assertThatThrownBy(() -> account.confirmReservedCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(1_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void releaseReservedCashDecreasesReservedCashWithoutChangingCashBalance() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(3_000_000L);

		account.releaseReservedCash(3_000_000L);

		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void releaseReservedCashThrowsIllegalStateExceptionWhenAmountExceedsReservedCash() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.reserveCash(1_000_000L);

		assertThatThrownBy(() -> account.releaseReservedCash(1_000_001L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(account.getReservedCash()).isEqualTo(1_000_000L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void addRealizedPnlIncreasesRealizedPnlWithPositiveAmount() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		account.addRealizedPnl(1_500L);

		assertThat(account.getRealizedPnl()).isEqualTo(1_500L);
	}

	@Test
	void addRealizedPnlAllowsNegativeAmountToAccumulateLoss() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);

		account.addRealizedPnl(-2_000L);

		assertThat(account.getRealizedPnl()).isEqualTo(-2_000L);
	}

	@Test
	void resetRestoresCashBalanceToInitialAmountRegardlessOfPriorState() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		account.deductCash(9_500_000L);

		account.reset(NOW.plusDays(1));

		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void resetClearsReservedCashRegardlessOfPriorReservation() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		account.reserveCash(4_000_000L);

		account.reset(NOW.plusDays(1));

		assertThat(account.getReservedCash()).isZero();
	}

	@Test
	void resetClearsRealizedPnlRegardlessOfPriorGainOrLoss() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		account.addRealizedPnl(3_300L);

		account.reset(NOW.plusDays(1));

		assertThat(account.getRealizedPnl()).isZero();
	}

	@Test
	void resetUpdatesTheUpdatedAtTimestamp() {
		TutorialAccount account = TutorialAccount.create(testUser(), Market.STOCK, NOW);
		LocalDateTime restartedAt = NOW.plusDays(1);

		account.reset(restartedAt);

		assertThat(account.getUpdatedAt()).isEqualTo(restartedAt);
	}

	@Test
	void resetSimultaneouslyClearsCashReservationAndPnlInOneCall() {
		// 재시작 시나리오: 잔고 소진 + 예약 + 손익 발생 상태에서 reset() 한 번으로 세 값이 모두
		// 동시에 초기화되는지 확인한다(TUTORIAL-CASH-ISOL-006 — "거래 내역 자체가 리셋된다").
		TutorialAccount account = TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
		account.deductCash(6_000_000L);
		account.reserveCash(1_000_000L);
		account.addRealizedPnl(-500_000L);

		account.reset(NOW.plusDays(1));

		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getRealizedPnl()).isZero();
	}

	private static User testUser() {
		return User.create("tutorial-trader@finplay.com", "password-hash", "tutorial-trader", NOW);
	}
}
