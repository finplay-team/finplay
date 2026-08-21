// 진입 재시도 경계가 교착을 1회만 삼키고 그 밖의 예외·2차 실패는 그대로 전파하는지 검증하는 단위 테스트
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.market.domain.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class PracticeAttemptEntryServiceTest {

	private static final Long USER_ID = 7L;

	private PracticeAttemptService practiceAttemptService;
	private PracticeAttemptEntryService practiceAttemptEntryService;

	@BeforeEach
	void setUp() {
		practiceAttemptService = mock(PracticeAttemptService.class);
		practiceAttemptEntryService = new PracticeAttemptEntryService(practiceAttemptService);
	}

	@Test
	void delegatesWithoutRetryWhenNoDeadlock() {
		PracticeAttemptResponse response = attemptResponse();
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO)).thenReturn(response);

		assertThat(practiceAttemptEntryService.ensureAttempt(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptService, times(1)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	@Test
	void retriesOnceAndReturnsResultWhenFirstCallDeadlocks() {
		PracticeAttemptResponse response = attemptResponse();
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO))
			.thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
			.thenReturn(response);

		assertThat(practiceAttemptEntryService.ensureAttempt(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptService, times(2)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	// 재시도는 1회다 — 두 번째도 교착이면 원인을 감추지 않고 그대로 전파한다(ADR-0028 §결정 2와 같은 방침).
	@Test
	void propagatesWhenRetryAlsoDeadlocks() {
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO))
			.thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"));

		assertThatThrownBy(() -> practiceAttemptEntryService.ensureAttempt(USER_ID, Market.CRYPTO))
			.isInstanceOf(CannotAcquireLockException.class);
		verify(practiceAttemptService, times(2)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	// 교착이 아닌 비즈니스 예외까지 재시도로 가리지 않는다.
	@Test
	void doesNotRetryBusinessException() {
		when(practiceAttemptService.ensureAttempt(eq(USER_ID), any(Market.class)))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		assertThatThrownBy(() -> practiceAttemptEntryService.ensureAttempt(USER_ID, Market.CRYPTO))
			.isInstanceOf(BusinessException.class);
		verify(practiceAttemptService, times(1)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	private PracticeAttemptResponse attemptResponse() {
		return new PracticeAttemptResponse(
			11L, "CRYPTO", 1L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L, "BALANCED", false, ExitPresetResponse.all());
	}
}
