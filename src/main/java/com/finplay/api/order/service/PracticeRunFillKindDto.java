// 현재 실행 세대에서 체결된 주문 하나의 매매 방향·주문 유형을 담는 order evidence DTO
package com.finplay.api.order.service;

import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;

/**
 * 튜토리얼 5단계 진행 판정(이슈 #503)이 "이 실행에서 시장가·지정가를 각각 사고팔았는가"를 세는 데 쓴다.
 *
 * <p><b>{@code orderId}가 필요한 이유</b>는 손절·익절 예약이 발동시킨 매도를 빼기 위해서다. 그 매도는
 * {@code ExitPlanFillService.executeMarketSell}이 만들며 원장에는 평범한 {@code MARKET} 주문으로 남는다 —
 * 주문 유형만 보면 프리셋에 청산당하기만 한 사용자가 "시장가로 팔아봤다"로 판정된다. 어떤 주문이 예약
 * 발동인지는 {@code exit_plans.triggered_order_id}만 알고 있고 그것은 education이 조회하므로, 이 DTO는
 * 주문 id를 그대로 넘겨 호출부가 걸러내게 한다.
 */
public record PracticeRunFillKindDto(Long orderId, OrderSide side, OrderType orderType) {
}
