// 대본 커서를 상태 전이표대로 전진시키고 건너뛴 가상 분마다 정산하는 진행 계산 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.market.service.TutorialScenarioScript;
import com.finplay.api.market.service.TutorialScenarioStage;
import com.finplay.api.market.service.TutorialScenarioStageKind;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import com.finplay.api.order.service.TradeService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ai/specs/041-tutorial-market-scenario/plan.md} §상태 전이표와 §tick 알고리즘을 구현한다.
 *
 * <p>표는 세 행뿐이고 <b>매도는 어느 행에도 없다</b>(SCENARIO-010) — 매도해도 커서를 옮기지 않는다. 가격을
 * 직접 계산하지 않고 {@code market}의 변환({@link PracticeAttemptCanonicalPriceService})만 호출한다(ADR-0002).
 */
@Service
@RequiredArgsConstructor
public class PracticeScenarioProgressService {

	// 탭을 닫았다 돌아온 사용자가 그 사이 시간을 통째로 소비하지 않게 한다. 시간 제한이 폐지된 지금 이 clamp의
	// 효과는 예산 절약이 아니라 "이야기를 건너뛰지 않는다" 하나다(041 plan §tick 알고리즘).
	private static final long MAX_TICK_GAP_SECONDS = 30L;
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;

	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final TradeService tradeService;

	/**
	 * 호출자가 이미 attempt를 비관 잠금한 트랜잭션 안에서만 부른다(현재 호출부는 {@code POST .../tick}).
	 * 생성기 버전 1 attempt는 대본을 쓰지 않으므로 아무 일도 하지 않는다.
	 */
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
		// 순회가 끝난 자리가 대기 구간인데 보유가 있으면 이번 tick 안에서 나간다. 대기 구간 끝에 닿아 0으로
		// 되감는 지점에서 체결되면 그때 남은 delta가 0이라 순회의 while 조건이 먼저 끝나기 때문이다
		// (041 4~5번 2차 리뷰가 "6번이 scenarioProgressing을 싣기 시작하면 화면에 보인다"고 넘긴 항목).
		boolean leftIdleLoop = leaveIdleLoopIfHolding(attempt, script, now);
		if (!enteredAnyMinute && !leftIdleLoop) {
			// 한 가상 분도 새로 진입하지 않은 tick(대본이 끝난 뒤, 같은 초의 재요청, 3초 미만 간격)도 정산은
			// 한다 — 생성기 버전 1은 tick마다 무조건 settleCurrentRun을 불렀고 그 보장을 잃으면 안 된다.
			// 이것이 없으면 대본 종료 후 접수한 지정가가 조건을 만족해도 영구히 PENDING으로 남는다.
			settle(attempt, now, canonicalPriceService.canonicalPrice(attempt, now));
		}
		// clamp되지 않았으면 소비한 초만큼만 기준을 민다 — now로 밀면 1초 미만 나머지가 매 tick 버려져
		// 3초의 배수가 아닌 간격으로 tick하는 클라이언트에서 대본이 조금씩 느려진다.
		attempt.markScenarioProgressed(clamped ? now : base.plusSeconds(gapSeconds));
	}

	// scenario_stage_id가 null이면 미시작이다 — 종목 선택·재시작이 다섯 컬럼을 전부 null로 지운다(041 3번이
	// 남긴 계약). 첫 tick이 대본의 첫 구간 0분으로 커서를 세우고 진행 중 봉을 연다.
	private void start(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now) {
		BigDecimal openPrice = canonicalPriceService.canonicalPrice(attempt, now);
		attempt.startScenarioProgress(script.firstStage().id(), openPrice, now);
		// 초기화 tick에도 이미 보유가 있으면 대기 구간에 세워 두지 않는다 — 종목 선택 직후 매수하고 첫
		// tick을 부른 사용자가 여기 해당하며, 미루면 화면이 한 사이클 동안 "대기 중"으로 보인다
		// (PR #494 QA 참고 3). 이동은 시간을 소비하지 않으므로 delta가 없는 이 tick에서 해도 대본이 앞서지 않는다.
		if (!leaveIdleLoopIfHolding(attempt, script, now)) {
			settle(attempt, now, openPrice);
		}
	}

	/**
	 * <b>한 tick이 끝났을 때 보유 중이면 커서는 대기 구간에 있지 않다</b> — 표 2행을 tick 경계에서도 지키는
	 * 마지막 방어다. 이동한 경우 {@link #exitIdleLoop}가 새 커서 가격으로 정산까지 마치므로 호출부는
	 * 폴백 정산을 생략한다.
	 *
	 * @return 실제로 대기 구간을 벗어났으면 {@code true}. 대기 구간이 아니거나 미보유거나 나갈 진행 구간이
	 *     없으면 {@code false}
	 */
	private boolean leaveIdleLoopIfHolding(
		PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now) {
		TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
		if (stage.kind() != TutorialScenarioStageKind.LOOP || netQuantity(attempt).signum() <= 0) {
			return false;
		}
		return exitIdleLoop(attempt, script, stage, now, 0L) >= 0L;
	}

	// 이번 호출에서 새 가상 분에 한 번이라도 진입했으면 true. 호출자가 진입 없는 tick의 정산을 보장한다.
	private boolean traverse(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now, long delta) {
		long remaining = delta;
		boolean entered = false;
		BigDecimal netQuantity = netQuantity(attempt);
		while (remaining > 0) {
			TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
			// 구간 길이가 0이면 이 순회가 끝나지 않는다 — 로더의 `minutes > 0` 기동 검증이 그것을 막는다.
			long stageSeconds = (long)stage.minutes() * SECONDS_PER_VIRTUAL_MINUTE;

			// 표 2행 — 대기 구간에서 보유가 생기면 시간을 소비하지 않고 다음 진행 구간의 0분으로 이동한다.
			// 이 전이가 없으면 사용자는 매수해도 0막을 영원히 돌고, 042의 도달 부등식이 가정한 진입 배율도
			// 무너진다.
			if (stage.kind() == TutorialScenarioStageKind.LOOP && netQuantity.signum() > 0) {
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
			// 대본을 편집해 구간을 짧게 줄이면 영속된 커서가 새 길이를 넘을 수 있다. 음수 step은 아래
			// `remaining -= consumed`를 덧셈으로 만들어 30초 clamp를 무력화하므로 0으로 막고, 아래 롤오버가
			// 커서를 다음 구간으로 정리하게 둔다.
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
					// 구간을 다 썼다 — 아래 롤오버가 다음 구간 0분을 새 분으로 취급한다.
					break;
				}
				// SCENARIO-013 — 건너뛴 가상 분마다 순차 정산한다. tick 종점 가격 하나로만 판정하면 그 사이
				// 10 가상 분의 극값을 못 봐 -3%로 걸어 둔 손절이 -10% 넘는 가격에 체결된다.
				enterMinute(attempt, stage.id(), elapsed, now, remaining - consumed);
				entered = true;
				netQuantity = netQuantity(attempt);
				if (stage.kind() == TutorialScenarioStageKind.LOOP && netQuantity.signum() > 0) {
					leftLoopEarly = true;
					break;
				}
			}
			if (!leftLoopEarly) {
				// 분 경계에 못 미친 잔여 초도 소비한다. 초 단위로 누적하는 이유가 이것이다 — 분으로 누적하면
				// 나머지가 매 tick 버려져 대본이 영영 진행하지 않는다.
				consumed = step;
				elapsed = target;
			}
			attempt.moveScenarioCursor(stage.id(), elapsed);
			// step이 아니라 실제로 소비한 초를 뺀다 — 안쪽 순회를 중간에 벗어나는 경우가 있으므로 step을
			// 그대로 빼면 소비하지 않은 시간이 조용히 사라져 대본이 느려진다.
			remaining -= consumed;
			if (leftLoopEarly) {
				// 남은 시간이 0이어도 이번 tick에서 진행 구간으로 나간다. while 조건에 맡기면 이동이 다음
				// tick으로 밀리는데, 3초 간격 클라이언트에서는 delta도 경과도 3의 배수라 그 조합이 예외가
				// 아니라 일반 경로다.
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
					// 표 1행 — 구간 끝에 닿으면 0으로 되감는다. 첫·끝 배율이 같아 경계에서 가격이 튀지 않는다.
					enterMinute(attempt, stage.id(), 0L, now, remaining);
				} else {
					Optional<TutorialScenarioStage> nextStage = script.nextStage(stage.id());
					if (nextStage.isEmpty()) {
						// 표 4행 — FINISHED. 더 진행하지 않고 마지막 가격을 유지한다.
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

	// 대기 구간 탈출을 한 곳에 모은다 — 순회 시작 시점에 이미 보유가 있는 경우와 순회 도중 체결로 보유가
	// 생기는 경우가 같은 규칙을 따라야 한다. 반환은 이동 뒤 남은 초이며, 다음 진행 구간이 없으면 -1이다.
	private long exitIdleLoop(
		PracticeAttempt attempt, TutorialScenarioScript script, TutorialScenarioStage stage, LocalDateTime now,
		long remaining) {
		Optional<TutorialScenarioStage> nextProgress = script.nextProgressStage(stage.id());
		if (nextProgress.isEmpty()) {
			return -1L;
		}
		// 남은 delta를 전부 이월하면 매수 직후 첫 화면이 진행 구간 한참 뒤가 되어 가격이 튄다.
		long truncated = Math.max(0L, Math.min(remaining, secondsSinceLatestBuy(attempt, now, remaining)));
		// 이동 자체는 시간을 소비하지 않으므로 절단 결과와 무관하게 수행한다.
		enterMinute(attempt, nextProgress.get().id(), 0L, now, truncated);
		return truncated;
	}

	// 커서를 실제로 밀고 나서 가격을 읽는다. 지정가 체결이 canonical 가격을 이 커서에서 파생하므로, 순회가
	// 끝난 뒤 한 번에 옮기면 건너뛴 분의 가격으로 정산할 수 없다(041 plan §`order` 인터페이스 변경).
	private void enterMinute(
		PracticeAttempt attempt, String stageId, long elapsedSeconds, LocalDateTime now, long remainingAfter) {
		attempt.moveScenarioCursor(stageId, elapsedSeconds);
		BigDecimal price = canonicalPriceService.canonicalPrice(attempt, now);
		attempt.extendScenarioCandle(price);
		settle(attempt, now.minusSeconds(Math.max(0L, remainingAfter)), price);
	}

	// 지정가와 OCO가 같은 가상 분에 같은 순서로 판정된다 — settleCurrentRun 안에서 지정가 → OCO 순이다
	// (042 EXITPRESET-014). 가격은 이 분의 대본 canonical price이므로 차트와 체결이 같은 값을 쓴다.
	private void settle(PracticeAttempt attempt, LocalDateTime pricedAt, BigDecimal canonicalPrice) {
		practiceOrderSettlementService.settleCurrentRun(
			attempt.getId(), attempt.getRunNumber(), pricedAt, canonicalPrice);
	}

	// 매 분 스칼라 집계 한 줄만 다시 읽는다 — 체결을 엔티티로 훑지 않고, 체결 서비스가 flush() 후 holding을
	// detach해도 영향을 받지 않는다(041 plan §tick 알고리즘). 042가 프리셋 잠금·진입 가드에 쓰는 것과 같은
	// 산출식이며, holdings 행의 수량이 아니라 **현재 실행 세대**의 순량이다.
	private BigDecimal netQuantity(PracticeAttempt attempt) {
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber());
	}

	// 체결 원장이 비어 있으면(보유는 있는데 이번 실행 체결이 없는 이례적 상태) 자르지 않는다.
	private long secondsSinceLatestBuy(PracticeAttempt attempt, LocalDateTime now, long remaining) {
		return tradeService.findLatestPracticeRunBuyExecutedAt(attempt.getId(), attempt.getRunNumber())
			.map(executedAt -> Math.max(0L, Duration.between(executedAt, now).getSeconds()))
			.orElse(remaining);
	}
}
