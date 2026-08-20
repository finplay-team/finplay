// 현재 실행 세대의 체결 원장을 읽어 튜토리얼 5단계 중 주문 방법·프리셋 단계의 완료 여부를 판정하는 조회 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.service.PracticeExitPlanQueryService;
import com.finplay.api.order.service.PracticeRunFillKindDto;
import com.finplay.api.order.service.TradeService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이슈 #503. <b>어떤 것도 저장하지 않는다</b> — 체결 원장, 예약 발동 이력, 위험 snapshot만 읽는다.
 *
 * <p><b>판정만 하고 강제하지 않는다.</b> 잘못된 순서의 주문을 거부하는 것은 주문 생성 경로에 새 검증을
 * 넣는 일이라 이 서비스의 범위가 아니다. 사용자가 API를 직접 불러 순서를 건너뛸 수는 있지만 이것은 보안이
 * 아니라 학습 순서다.
 */
@Service
@RequiredArgsConstructor
public class PracticeStageProgressCalculationService {

	private final TradeService tradeService;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	@Transactional(readOnly = true)
	public PracticeStageProgressResponse calculate(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return PracticeStageProgressResponse.none();
		}
		Long attemptId = attempt.getId();
		long runNumber = attempt.getRunNumber();

		// 예약이 발동시킨 매도 주문 id. 042가 매도 원인을 붙일 때 쓰는 것과 같은 조회라 새 쿼리가 아니다.
		Set<Long> triggeredSellOrderIds = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attemptId, runNumber)
			.keySet();
		List<PracticeRunFillKindDto> fills = tradeService.findPracticeRunFillKinds(attemptId, runNumber);

		return new PracticeStageProgressResponse(
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.MARKET),
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.LIMIT),
			// 이 실행에서 프리셋을 직접 골랐는가. 재시작이 attempt.exitPreset을 지우므로 실행 안에서
			// 단조롭게 증가한다 — 한 번 통과한 단계가 드롭다운 조작만으로 되잠기지 않는다.
			attempt.getExitPreset() != null);
	}

	/**
	 * 그 주문 유형으로 <b>사고 판</b> 것까지 마쳤는가.
	 *
	 * <p><b>매도에서 예약 발동분을 뺀다.</b> 손절·익절 청산은 {@code ExitPlanFillService.executeMarketSell}이
	 * 만들어 원장에 {@code MARKET} 매도로 남는다 — 빼지 않으면 프리셋에 청산당하기만 한 사용자가 시장가
	 * 단계를 통과한 것으로 표시된다. 매수 쪽은 뺄 것이 없다(예약은 매도만 만든다).
	 *
	 * <p>재시작 보상 매도({@code PracticeRunRestartOrderService})도 {@code MARKET}이지만 <b>직전 실행 세대에
	 * 귀속</b>되므로 이 판정에 애초에 들어오지 않는다 — 재시작이 run을 올리기 전의 번호로 원장을 남긴다.
	 */
	private boolean roundTripCompleted(
		List<PracticeRunFillKindDto> fills, Set<Long> triggeredSellOrderIds, OrderType orderType) {
		boolean bought = false;
		boolean sold = false;
		for (PracticeRunFillKindDto fill : fills) {
			if (fill.orderType() != orderType) {
				continue;
			}
			if (fill.side() == OrderSide.BUY) {
				bought = true;
			} else if (!triggeredSellOrderIds.contains(fill.orderId())) {
				sold = true;
			}
		}
		return bought && sold;
	}

}
