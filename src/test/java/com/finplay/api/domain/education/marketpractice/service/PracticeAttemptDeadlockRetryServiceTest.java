// 재시도 경계가 진입·재시작·tick 각각에서 교착을 1회만 삼키고 그 밖의 예외·2차 실패는 그대로 전파하는지 검증한다
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.ExitRateBoundsResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class PracticeAttemptDeadlockRetryServiceTest {

	private static final Long USER_ID = 7L;

	private PracticeAttemptService practiceAttemptService;
	private PracticeAttemptRestartService practiceAttemptRestartService;
	private PracticeAttemptChartService practiceAttemptChartService;
	private PracticeAttemptDeadlockRetryService retryService;

	@BeforeEach
	void setUp() {
		practiceAttemptService = mock(PracticeAttemptService.class);
		practiceAttemptRestartService = mock(PracticeAttemptRestartService.class);
		practiceAttemptChartService = mock(PracticeAttemptChartService.class);
		retryService = new PracticeAttemptDeadlockRetryService(
			practiceAttemptService, practiceAttemptRestartService, practiceAttemptChartService);
	}

	@Test
	void delegatesWithoutRetryWhenNoDeadlock() {
		PracticeAttemptResponse response = attemptResponse();
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO)).thenReturn(response);

		assertThat(retryService.ensureAttempt(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptService, times(1)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	@Test
	void retriesEnsureAttemptOnceWhenFirstCallDeadlocks() {
		PracticeAttemptResponse response = attemptResponse();
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO))
			.thenThrow(deadlock())
			.thenReturn(response);

		assertThat(retryService.ensureAttempt(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptService, times(2)).ensureAttempt(USER_ID, Market.CRYPTO);
	}

	// 이슈 #491 코멘트가 두 번째 재현 경로로 등록한 조합이다. 재현하지는 못했지만 그물은 여기에도 둔다.
	@Test
	void retriesRestartOnceWhenFirstCallDeadlocks() {
		PracticeAttemptResponse response = attemptResponse();
		when(practiceAttemptRestartService.restart(USER_ID, Market.CRYPTO))
			.thenThrow(deadlock())
			.thenReturn(response);

		assertThat(retryService.restart(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptRestartService, times(2)).restart(USER_ID, Market.CRYPTO);
	}

	@Test
	void retriesTickOnceWhenFirstCallDeadlocks() {
		PracticeTutorialChartResponse response = mock(PracticeTutorialChartResponse.class);
		when(practiceAttemptChartService.tick(USER_ID, Market.CRYPTO))
			.thenThrow(deadlock())
			.thenReturn(response);

		assertThat(retryService.tick(USER_ID, Market.CRYPTO)).isSameAs(response);
		verify(practiceAttemptChartService, times(2)).tick(USER_ID, Market.CRYPTO);
	}

	// 재시도는 1회다 — 두 번째도 교착이면 원인을 감추지 않고 그대로 전파한다(ADR-0028 §결정 2와 같은 방침).
	@Test
	void propagatesWhenRetryAlsoDeadlocks() {
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO)).thenThrow(deadlock());
		when(practiceAttemptRestartService.restart(USER_ID, Market.CRYPTO)).thenThrow(deadlock());
		when(practiceAttemptChartService.tick(USER_ID, Market.CRYPTO)).thenThrow(deadlock());

		assertThatThrownBy(() -> retryService.ensureAttempt(USER_ID, Market.CRYPTO))
			.isInstanceOf(CannotAcquireLockException.class);
		assertThatThrownBy(() -> retryService.restart(USER_ID, Market.CRYPTO))
			.isInstanceOf(CannotAcquireLockException.class);
		assertThatThrownBy(() -> retryService.tick(USER_ID, Market.CRYPTO))
			.isInstanceOf(CannotAcquireLockException.class);

		verify(practiceAttemptService, times(2)).ensureAttempt(USER_ID, Market.CRYPTO);
		verify(practiceAttemptRestartService, times(2)).restart(USER_ID, Market.CRYPTO);
		verify(practiceAttemptChartService, times(2)).tick(USER_ID, Market.CRYPTO);
	}

	// 교착이 아닌 비즈니스 예외까지 재시도로 가리지 않는다 — 세 경로 모두 409를 그대로 통과시켜야 한다.
	@Test
	void doesNotRetryBusinessException() {
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		when(practiceAttemptRestartService.restart(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		when(practiceAttemptChartService.tick(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		assertThatThrownBy(() -> retryService.ensureAttempt(USER_ID, Market.CRYPTO))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> retryService.restart(USER_ID, Market.CRYPTO))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> retryService.tick(USER_ID, Market.CRYPTO))
			.isInstanceOf(BusinessException.class);

		verify(practiceAttemptService, times(1)).ensureAttempt(USER_ID, Market.CRYPTO);
		verify(practiceAttemptRestartService, times(1)).restart(USER_ID, Market.CRYPTO);
		verify(practiceAttemptChartService, times(1)).tick(USER_ID, Market.CRYPTO);
	}

	private CannotAcquireLockException deadlock() {
		return new CannotAcquireLockException("Deadlock found when trying to get lock");
	}

	private PracticeAttemptResponse attemptResponse() {
		return new PracticeAttemptResponse(
			11L, "CRYPTO", 1L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L, "BALANCED", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
	}
}
