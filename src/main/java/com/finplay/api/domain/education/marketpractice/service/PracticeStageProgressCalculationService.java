// 현재 실행 세대의 체결 원장을 읽어 튜토리얼 5단계 중 주문 방법·프리셋 단계의 완료 여부를 판정하는 조회 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunFillKindDto;
import com.finplay.api.domain.order.service.TradeService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이슈 #503. <b>어떤 것도 저장하지 않는다</b> — 체결 원장과 예약 원장, 그리고 attempt에 이미 실려 있는
 * 손절·익절 기준값만 읽는다. <b>세 값의 근거가 같지 않다</b> — 왕복 둘은 체결 원장에서 나오고, 기준 단계는
 * attempt의 비율 컬럼과 (052 2차부터) 그 실행의 예약 존재 여부에서 나온다.
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

		// 예약이 발동시킨 매도 주문 id.
		//
		// **같은 요청에서 이 조회가 한 번 더 나간다.** 진입이 하나라도 있으면
		// PracticeEntryComparisonService가 매도 원인을 붙이려고 같은 인자로 먼저 부른다. 파생 쿼리라
		// 영속성 컨텍스트가 SQL을 막아 주지 않는다. 두 서비스가 서로를 모르게 두는 값이 인덱스 조회
		// 하나보다 크다고 보고 그대로 둔다 — 없애려면 호출부가 map을 조회해 둘에 넘겨야 한다.
		Set<Long> triggeredSellOrderIds = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attemptId, runNumber)
			.keySet();
		List<PracticeRunFillKindDto> fills = tradeService.findPracticeRunFillKinds(attemptId, runNumber);

		return new PracticeStageProgressResponse(
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.MARKET),
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.LIMIT),
			exitStandardChosen(attempt));
	}

	/**
	 * 이 실행에서 손절·익절 기준을 <b>직접 정한 적이 있는가</b>(5단계 중 4단계, TUTORIAL-STAGE-001).
	 *
	 * <p><b>이 판정은 두 번 넓어졌고, 두 번 다 화면에서 입력 경로가 사라졌기 때문이다.</b> 좁히기 쉬운
	 * 자리라 이유를 남긴다 — 판정 근거를 "지금 화면이 부르는 API"에 맞추면 화면이 바뀔 때마다 이 단계가
	 * 조용히 영영 미완으로 남는다.
	 *
	 * <ol>
	 * <li><b>052 1차</b> — 프리셋 택1이 자유 입력으로 바뀌면서 {@code exit_preset}만 보던 판정이
	 * {@code exit_stop_loss_rate}까지 보게 됐다({@code attempt.exitRatesSelected()}).</li>
	 * <li><b>052 2차(EXITFREE-020)</b> — 예약을 거는 주체가 서버에서 사용자로 바뀌면서 3단계 화면이
	 * {@code PUT .../exit-rates}를 아예 부르지 않게 됐다. 사용자는 <b>예약 요청 본문에</b> 비율을 적고,
	 * 그 값은 attempt 컬럼이 아니라 예약 자체에 남는다. 그래서 <b>예약을 만든 적이 있으면</b> 그것도
	 * "기준을 정했다"로 센다.</li>
	 * </ol>
	 *
	 * <p><b>실행 안에서 단조 증가한다.</b> 두 근거 모두 지워지지 않는다 — 비율 컬럼은 다시 정해도 non-null로
	 * 남고, 예약 행은 취소·체결돼도 상태만 바뀌지 사라지지 않는다(그래서 상태를 묻지 않는다). 예약을 걸었다
	 * 취소했다고 이미 연 단계가 되잠기면 화면이 사용자 눈앞에서 되감긴다. 재시작은 두 컬럼을 지우고 실행
	 * 세대를 올려 이전 예약을 조회 범위에서 빼므로 <b>초기화만 정확히</b> 성립한다.
	 *
	 * <p><b>대본을 쓰지 않는 실행의 예약은 근거로 세지 않는다.</b> 그쪽은 042 그대로 매수 체결이 서버가
	 * 자동으로 거는 예약이라, 사용자가 아무것도 고르지 않아도 행이 생긴다 — 그것으로 통과시키면
	 * TUTORIAL-STAGE-001이 배제한 "고른 값으로 진입까지 했는가"류의 오판이 그대로 되살아난다. 대본 실행에는
	 * 자동 예약이 아예 없으므로(EXITFREE-020) 거기 있는 예약은 전부 사용자가 만든 것이다.
	 */
	private boolean exitStandardChosen(PracticeAttempt attempt) {
		if (attempt.exitRatesSelected()) {
			return true;
		}
		if (attempt.scenarioScriptId() == null) {
			return false;
		}
		return practiceExitPlanQueryService.existsRunReservation(attempt.getId(), attempt.getRunNumber());
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
