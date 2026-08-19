// 대본 커서를 상태 전이표대로 전진시키고 건너뛴 가상 분마다 정산하는 진행 계산 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.market.service.TutorialScenarioScript;
import com.finplay.api.market.service.TutorialScenarioStage;
import com.finplay.api.market.service.TutorialScenarioStageKind;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code docs/specs/041-tutorial-market-scenario/plan.md} §상태 전이표와 §tick 알고리즘을 구현한다.
 *
 * <p>표는 세 행뿐이고 <b>매도는 어느 행에도 없다</b>(SCENARIO-010) — 매도해도 커서를 옮기지 않는다. 가격을
 * 직접 계산하지 않고 {@code market}의 변환({@link PracticeAttemptCanonicalPriceService})만 호출한다(ADR-0002).
 */
@Service
@RequiredArgsConstructor
public class PracticeScenarioProgressService {

	// 탭을 닫았다 돌아온 사용자가 그 사이 시간을 통째로 소비하지 않게 한다. 시간 제한이 폐지된 지금 이 clamp의
	// 효과는 예산 절약이 아니라 "이야기를 건너뛰지 않는다" 하나다(041 plan §tick 알고리즘).
	static final long MAX_TICK_GAP_SECONDS = 30L;
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;

	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final HoldingService holdingService;
	private final TradeService tradeService;

	/**
	 * 호출자가 이미 attempt를 비관 잠금한 트랜잭션 안에서만 부른다(현재 호출부는 {@code POST .../tick}).
	 * 생성기 버전 1 attempt는 대본을 쓰지 않으므로 아무 일도 하지 않는다.
	 */
	@Transactional
	public void advance(PracticeAttempt attempt, LocalDateTime now) {
		if (!canonicalPriceService.isScenarioVersion(attempt)) {
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
		traverse(attempt, script, now, clamped ? MAX_TICK_GAP_SECONDS : gapSeconds);
		// clamp되지 않았으면 소비한 초만큼만 기준을 민다 — now로 밀면 1초 미만 나머지가 매 tick 버려져
		// 3초의 배수가 아닌 간격으로 tick하는 클라이언트에서 대본이 조금씩 느려진다.
		attempt.markScenarioProgressed(clamped ? now : base.plusSeconds(gapSeconds));
	}

	// scenario_stage_id가 null이면 미시작이다 — 종목 선택·재시작이 다섯 컬럼을 전부 null로 지운다(041 3번이
	// 남긴 계약). 첫 tick이 대본의 첫 구간 0분으로 커서를 세우고 진행 중 봉을 연다.
	private void start(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now) {
		BigDecimal openPrice = canonicalPriceService.canonicalPrice(attempt, now);
		attempt.startScenarioProgress(script.firstStage().id(), openPrice, now);
		settle(attempt, now);
	}

	private void traverse(PracticeAttempt attempt, TutorialScenarioScript script, LocalDateTime now, long delta) {
		long remaining = delta;
		BigDecimal netQuantity = netQuantity(attempt);
		while (remaining > 0) {
			TutorialScenarioStage stage = script.stage(attempt.getScenarioStageId());
			long stageSeconds = (long)stage.minutes() * SECONDS_PER_VIRTUAL_MINUTE;

			// 표 2행 — 대기 구간에서 보유가 생기면 시간을 소비하지 않고 다음 진행 구간의 0분으로 이동한다.
			// 이 전이가 없으면 사용자는 매수해도 0막을 영원히 돌고, 042의 도달 부등식이 가정한 진입 배율도
			// 무너진다.
			if (stage.kind() == TutorialScenarioStageKind.LOOP && netQuantity.signum() > 0) {
				Optional<TutorialScenarioStage> nextProgress = script.nextProgressStage(stage.id());
				if (nextProgress.isEmpty()) {
					break;
				}
				// 남은 delta를 전부 이월하면 매수 직후 첫 화면이 진행 구간 한참 뒤가 되어 가격이 튄다.
				remaining = Math.min(remaining, secondsSinceLatestBuy(attempt, now, remaining));
				// 이동 자체는 시간을 소비하지 않으므로 절단 결과와 무관하게 먼저 한다 — 체결이 방금 일어나
				// 남은 시간이 0이어도 사용자는 이번 tick에서 진행 구간 0분을 본다.
				enterMinute(attempt, nextProgress.get().id(), 0L, now, remaining);
				netQuantity = netQuantity(attempt);
				if (remaining <= 0) {
					break;
				}
				continue;
			}

			long elapsed = attempt.getScenarioStageElapsedSeconds();
			long step = Math.min(remaining, stageSeconds - elapsed);
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
				netQuantity = netQuantity(attempt);
			}
		}
	}

	// 커서를 실제로 밀고 나서 가격을 읽는다. 지정가 체결이 canonical 가격을 이 커서에서 파생하므로, 순회가
	// 끝난 뒤 한 번에 옮기면 건너뛴 분의 가격으로 정산할 수 없다(041 plan §`order` 인터페이스 변경).
	private void enterMinute(
		PracticeAttempt attempt, String stageId, long elapsedSeconds, LocalDateTime now, long remainingAfter) {
		attempt.moveScenarioCursor(stageId, elapsedSeconds);
		attempt.extendScenarioCandle(canonicalPriceService.canonicalPrice(attempt, now));
		settle(attempt, now.minusSeconds(Math.max(0L, remainingAfter)));
	}

	// 042 6번이 이 자리에 OCO 예약 정산 루프를 얹는다 — settleCurrentRun 안에 넣으면 지정가와 OCO가 같은
	// 가상 분에 같은 순서로 판정된다(041 plan §`order` 인터페이스 변경의 표 3행).
	private void settle(PracticeAttempt attempt, LocalDateTime pricedAt) {
		practiceOrderSettlementService.settleCurrentRun(attempt.getId(), attempt.getRunNumber(), pricedAt);
	}

	// 매 분 전체 체결을 다시 스캔하지 않고 holding 수량 스칼라만 다시 읽는다. 체결 서비스가 flush() 후
	// holding을 detach하므로 캐시한 인스턴스를 재사용하면 낡은 수량을 읽는다(041 plan §tick 알고리즘).
	private BigDecimal netQuantity(PracticeAttempt attempt) {
		return holdingService.findNetQuantity(
			attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId());
	}

	// 체결 원장이 비어 있으면(보유는 있는데 이번 실행 체결이 없는 이례적 상태) 자르지 않는다.
	private long secondsSinceLatestBuy(PracticeAttempt attempt, LocalDateTime now, long remaining) {
		return tradeService.findLatestPracticeRunBuyExecutedAt(attempt.getId(), attempt.getRunNumber())
			.map(executedAt -> Math.max(0L, Duration.between(executedAt, now).getSeconds()))
			.orElse(remaining);
	}
}
