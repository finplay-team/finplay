// 실행 세대의 진입마다 기준선·매수·매도와 "안 팔았다면" 평가손익을 묶어 완료 대조 배열을 만드는 조회 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 041 SCENARIO-019b·021·021a. 042가 재진입을 열면서 완료 화면이 <b>그 실행의 첫 매도만</b> 가리키게 된
 * 결함을 닫는다 — 2막 손절 → 3막 익절한 사용자에게 손절 하나만 보이던 것이 진입 둘로 갈라진다.
 *
 * <p><b>어떤 것도 저장하지 않는다.</b> 위험 snapshot(진입의 정의)과 체결 원장, 예약 발동 이력, 대본 가격만
 * 읽는다.
 */
@Service
@RequiredArgsConstructor
public class PracticeEntryComparisonService {

	// OrderExecutionService.priceOrder·PostSellArithmetic.feeRateOf와 **같은 값이어야 한다.** 그 두 곳의
	// 필드는 private이고 각각 다른 트랜잭션 경계·다른 도메인에 있어, PostSellArithmetic이 그랬던 것과 같은
	// 이유로 의존을 만들지 않고 값만 맞춘다. 요율이 바뀌면 이 상수도 함께 본다.
	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final TradeService tradeService;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	/**
	 * 현재 실행 세대의 진입별 대조 배열. 진입 순번 오름차순이며 진입이 없으면(매수 전) 빈 목록이다.
	 *
	 * @param comparisonPrice {@link PracticeAttemptCanonicalPriceService#postSellComparisonPrice}가 고른
	 *     "안 팔았다면"의 기준 가격. {@code null}이면 {@code unrealizedPnlIfHeld}를 채우지 않는다
	 */
	@Transactional(readOnly = true)
	public List<PracticeEntryResponse> findCurrentRunEntries(PracticeAttempt attempt, BigDecimal comparisonPrice) {
		List<PracticeRiskSnapshot> snapshots = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(attempt.getId(), attempt.getRunNumber());
		if (snapshots.isEmpty()) {
			return List.of();
		}
		List<PracticeRunTradeSummaryDto> summaries = tradeService.summarizePracticeRunEntries(
			attempt.getId(),
			attempt.getRunNumber(),
			snapshots.stream().map(snapshot -> snapshot.getBuyTrade().getId()).toList());
		Map<Long, ExitPlanStatus> triggered = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attempt.getId(), attempt.getRunNumber());

		List<PracticeEntryResponse> entries = new ArrayList<>(snapshots.size());
		for (int index = 0; index < snapshots.size(); index++) {
			entries.add(toEntry(snapshots.get(index), summaries.get(index), triggered, attempt, comparisonPrice));
		}
		return List.copyOf(entries);
	}

	private PracticeEntryResponse toEntry(
		PracticeRiskSnapshot snapshot,
		PracticeRunTradeSummaryDto summary,
		Map<Long, ExitPlanStatus> triggeredSellOrderStatuses,
		PracticeAttempt attempt,
		BigDecimal comparisonPrice) {
		Trade sellTrade = summary.firstSellTrade();
		Market market = attempt.getMarket();
		return new PracticeEntryResponse(
			snapshot.getEntrySequence(),
			// 기능 도입 전에 만들어진 행은 exit_preset이 null이며 기본 프리셋으로 해석한다(042 EXITPRESET-002).
			// PracticeAttemptResponse.selectedExitPreset과 같은 규칙이라 화면 두 곳이 다른 값을 보이지 않는다.
			(snapshot.getExitPreset() == null ? ExitPreset.DEFAULT : snapshot.getExitPreset()).name(),
			// 이슈 #503 — 진입을 연 매수의 주문 유형. 진입 경계가 곧 그 매수 체결이다. buyTrade와 그
			// order는 지연 로딩이지만 snapshot 조회가 @EntityGraph로 함께 가져오므로 여기서 추가 조회가
			// 나지 않는다 — 그 fetch가 빠지면 진입 하나마다 조회 두 번이 조용히 붙는다.
			snapshot.getBuyTrade().getOrder().getOrderType().name(),
			snapshot.getBuyTrade().getExecutedAt(),
			summary.averageBuyPrice(),
			summary.buyQuantity(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			summary.averageSellPrice(),
			summary.sellQuantity(),
			sellTrade == null ? null : sellTrade.getExecutedAt(),
			sellTrade == null
				? null
				: PracticeSellCause.from(triggeredSellOrderStatuses.get(sellTrade.getOrder().getId())).name(),
			summary.realizedPnl(),
			unrealizedPnlIfHeld(summary, market, comparisonPrice),
			scenarioScriptIdOf(attempt, snapshot));
	}

	/**
	 * 049 ORDERBASICS-023 — 이 진입이 열릴 때 attempt가 쓰던 대본. NULL 해석을 엔티티가 아니라 여기 두는
	 * 이유는 {@code PracticeRiskSnapshot.attempt}가 지연 로딩이고, 판정에 필요한
	 * {@code attempt.usesScenarioScript()}를 호출자가 이미 인자로 든 {@code attempt}로 공짜로 얻을 수 있기
	 * 때문이다(plan.md §3-A) — {@code exitPreset}이 같은 자리에서 쓰는 것과 같은 패턴이다.
	 */
	private String scenarioScriptIdOf(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
		if (!attempt.usesScenarioScript()) {
			return null;
		}
		TutorialScenarioScriptId scenarioScriptId = snapshot.getScenarioScriptId();
		return (scenarioScriptId == null ? TutorialScenarioScriptId.CRYPTO_STORY_V1 : scenarioScriptId).name();
	}

	/**
	 * {@code (기준가 × 팔린 수량 − 그 금액의 매도수수료) − (배분 매수원가 + 배분 매수수수료)}.
	 *
	 * <p><b>식과 라운딩이 {@code OrderExecutionService.priceOrder}·{@code PostSellArithmetic
	 * .counterfactualReturnRate}와 같다</b> — 매도금액을 원 단위로 {@code FLOOR}한 뒤 그 금액에 요율을 곱해
	 * 다시 {@code FLOOR}한다. 가격이 바뀌면 수수료도 바뀌므로 실제 체결의 수수료를 그대로 쓸 수 없다.
	 *
	 * <p><b>매도 수수료를 빼는 것이 이 값의 존재 이유다.</b> 비교 대상인 {@code realizedPnl}은 매수·매도
	 * 수수료가 모두 반영된 원장 값이라, 가상 보유 쪽에서 매도 수수료를 빼지 않으면 항상 조금 유리해 보인다.
	 *
	 * <p>분모가 되는 {@code soldBuyBasis}는 <b>팔린 수량에 배분된</b> 매수원가라, 곱하는 수량도 매수 수량이
	 * 아니라 <b>팔린 수량</b>이어야 두 항의 기준이 같아진다.
	 */
	private Long unrealizedPnlIfHeld(
		PracticeRunTradeSummaryDto summary, Market market, BigDecimal comparisonPrice) {
		if (comparisonPrice == null || summary.soldBuyBasis() == null || summary.sellQuantity().signum() <= 0) {
			return null;
		}
		long amount = comparisonPrice.multiply(summary.sellQuantity())
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		long fee = BigDecimal.valueOf(amount)
			.multiply(market == Market.CRYPTO ? CRYPTO_FEE_RATE : STOCK_FEE_RATE)
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		return (amount - fee) - summary.soldBuyBasis();
	}
}
