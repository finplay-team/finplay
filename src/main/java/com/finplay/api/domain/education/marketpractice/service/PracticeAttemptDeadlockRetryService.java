package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.market.entity.Market;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeAttemptDeadlockRetryService {

	private final PracticeAttemptService practiceAttemptService;
	private final PracticeAttemptRestartService practiceAttemptRestartService;
	private final PracticeAttemptChartService practiceAttemptChartService;

	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		return retryOnce("튜토리얼 진입", userId, market,
			() -> practiceAttemptService.ensureAttempt(userId, market));
	}

	public PracticeAttemptResponse restart(Long userId, Market market) {
		return retryOnce("튜토리얼 재시작", userId, market,
			() -> practiceAttemptRestartService.restart(userId, market));
	}

	public PracticeTutorialChartResponse tick(Long userId, Market market) {
		return retryOnce("튜토리얼 tick", userId, market,
			() -> practiceAttemptChartService.tick(userId, market));
	}

	private <T> T retryOnce(String operation, Long userId, Market market, Supplier<T> action) {
		try {
			return action.get();
		} catch (CannotAcquireLockException deadlock) {
			log.warn("{} 중 교착 발생 — 1회 재시도한다. userId={}, market={}", operation, userId, market, deadlock);
			return action.get();
		}
	}
}
