package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.market.service.TutorialScenarioStage;
import com.finplay.api.domain.market.service.TutorialScenarioStageKind;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeScenarioProgressService {

	private static final long MAX_TICK_GAP_SECONDS = 30L;
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;

	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final TradeService tradeService;
	private final PracticeExitPlanReservationService practiceExitPlanReservationService;

	@Transactional
	public void advance(PracticeAttempt attempt, LocalDateTime now) {
		if (!attempt.usesScenarioScript()) {
			return;
		}
		TutorialScenarioScript script = canonicalPriceService.script(attempt);
		if (attempt.getScenarioStageId() == null
			|| attempt.getScenarioStageElapsedSeconds() == null
			|| attempt.getScenarioCandleOpen() == null
			|| attempt.getScenarioProgressUpdatedAt() == null) {
			start(attempt, script, now);
			return;
		}

		LocalDateTime base = attempt.getScenarioProgressUpdatedAt();
		long gapSeconds = Math.max(0L, Duration.between(base, now).getSeconds());
		boolean clamped = gapSeconds > MAX_TICK_GAP_SECONDS;
		boolean enteredAnyMinute = traverse(attempt, script, now, clamped ? MAX_TICK_GAP_SECONDS : gapSeconds);
		boolean leftIdleLoop = leaveIdleLoopIfReady(attempt, script, now);
		if (!enteredAnyMinute && !leftIdleLoop) {
			settle(attempt, now, canonicalPriceService.canonicalPrice(attempt, now));
		}
		attempt.markScenarioProgressed(clamped ? now : base.plusSeconds(gapSeconds));
	}

	private void start(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now) {
		BigDecimal openPrice = canonicalPriceService.canonicalPrice(attempt, now);
		attempt.startScenarioProgress(script.firstStage().id(), openPrice, now);
		if (!leaveIdleLoopIfReady(attempt, script, now)) {
			settle(attempt, now, openPrice);
		}
	}

	private boolean leaveIdleLoopIfReady(
		PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now) {
		TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
		if (stage.kind() != TutorialScenarioStageKind.LOOP || !readyToLeaveIdleLoop(attempt, netQuantity(attempt))) {
			return false;
		}
		return exitIdleLoop(attempt, script, stage, now, 0L) >= 0L;
	}

	private boolean traverse(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now, long delta) {
		long remaining = delta;
		boolean entered = false;
		BigDecimal netQuantity = netQuantity(attempt);
		while (remaining > 0) {
			TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
			long stageSeconds = (long)stage.minutes() * SECONDS_PER_VIRTUAL_MINUTE;

			if (stage.kind() == TutorialScenarioStageKind.LOOP && readyToLeaveIdleLoop(attempt, netQuantity)) {
				long truncated = exitIdleLoop(attempt, script, stage, now, remaining);
				if (truncated < 0) {
					break;
				}
				entered = true;
				remaining = truncated;
				netQuantity = netQuantity(attempt);
				if (remaining <= 0) {
					break;
				}
				continue;
			}

			long elapsed = attempt.getScenarioStageElapsedSeconds();
			long step = Math.min(remaining, Math.max(0L, stageSeconds - elapsed));
			long target = elapsed + step;
			long consumed = 0L;
			boolean leftLoopEarly = false;
			while (true) {
				long nextBoundary = (elapsed / SECONDS_PER_VIRTUAL_MINUTE + 1) * SECONDS_PER_VIRTUAL_MINUTE;
				if (nextBoundary > target) {
					break;
				}
				consumed += nextBoundary - elapsed;
				elapsed = nextBoundary;
				long minute = elapsed / SECONDS_PER_VIRTUAL_MINUTE;
				if (minute >= stage.minutes()) {
					break;
				}
				enterMinute(attempt, stage.id(), elapsed, now, remaining - consumed);
				entered = true;
				netQuantity = netQuantity(attempt);
				if (stage.kind() == TutorialScenarioStageKind.LOOP && readyToLeaveIdleLoop(attempt, netQuantity)) {
					leftLoopEarly = true;
					break;
				}
			}
			if (!leftLoopEarly) {
				consumed = step;
				elapsed = target;
			}
			attempt.moveScenarioCursor(stage.id(), elapsed);
			remaining -= consumed;
			if (leftLoopEarly) {
				long truncated = exitIdleLoop(attempt, script, stage, now, remaining);
				if (truncated < 0) {
					break;
				}
				remaining = truncated;
				netQuantity = netQuantity(attempt);
				if (remaining <= 0) {
					break;
				}
				continue;
			}
			if (elapsed >= stageSeconds) {
				if (stage.kind() == TutorialScenarioStageKind.LOOP) {
					enterMinute(attempt, stage.id(), 0L, now, remaining);
				} else {
					Optional<TutorialScenarioStage> nextStage = script.nextStage(stage.id());
					if (nextStage.isEmpty()) {
						break;
					}
					enterMinute(attempt, nextStage.get().id(), 0L, now, remaining);
				}
				entered = true;
				netQuantity = netQuantity(attempt);
			}
		}
		return entered;
	}

	private long exitIdleLoop(
		PracticeAttempt attempt, TutorialScenarioScript script, TutorialScenarioStage stage, LocalDateTime now,
		long remaining) {
		Optional<TutorialScenarioStage> nextProgress = script.nextProgressStage(stage.id());
		if (nextProgress.isEmpty()) {
			return -1L;
		}
		long truncated = Math.max(0L, Math.min(remaining, secondsSinceLatestBuy(attempt, now, remaining)));
		enterMinute(attempt, nextProgress.get().id(), 0L, now, truncated);
		return truncated;
	}

	private void enterMinute(
		PracticeAttempt attempt, String stageId, long elapsedSeconds, LocalDateTime now, long remainingAfter) {
		attempt.moveScenarioCursor(stageId, elapsedSeconds);
		BigDecimal price = canonicalPriceService.canonicalPrice(attempt, now);
		attempt.extendScenarioCandle(price);
		settle(attempt, now.minusSeconds(Math.max(0L, remainingAfter)), price);
	}

	private void settle(PracticeAttempt attempt, LocalDateTime pricedAt, BigDecimal canonicalPrice) {
		practiceOrderSettlementService.settleCurrentRun(
			attempt.getId(), attempt.getRunNumber(), pricedAt, canonicalPrice);
	}

	private BigDecimal netQuantity(PracticeAttempt attempt) {
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber());
	}

	private boolean readyToLeaveIdleLoop(PracticeAttempt attempt, BigDecimal netQuantity) {
		return netQuantity.signum() > 0 && practiceExitPlanReservationService.entryReservationSatisfied(attempt);
	}

	private long secondsSinceLatestBuy(PracticeAttempt attempt, LocalDateTime now, long remaining) {
		return tradeService.findLatestPracticeRunBuyExecutedAt(attempt.getId(), attempt.getRunNumber())
			.map(executedAt -> Math.max(0L, Duration.between(executedAt, now).getSeconds()))
			.orElse(remaining);
	}
}
