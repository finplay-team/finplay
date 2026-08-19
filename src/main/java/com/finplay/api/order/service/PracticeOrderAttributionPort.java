// order 도메인이 튜토리얼 attempt 잠금·진입 BUY snapshot을 요청하는 애플리케이션 포트
package com.finplay.api.order.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.Trade;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PracticeOrderAttributionPort {

	Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument);

	PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt);

	/**
	 * BUY 체결마다 불린다. <b>진입당 1회만</b> snapshot을 만드는 판정은 구현이 한다(042 EXITPRESET-020) —
	 * 보유 중 추가 매수는 체결만 되고 기준선이 움직이지 않는다. 이름이 {@code First}였던 것을 바꾼 이유는
	 * 재진입(손절 후 재매수)에서도 새 snapshot이 생기기 때문이다.
	 */
	void createRiskSnapshotOnBuyFill(Order order, Trade trade, LocalDateTime createdAt);

}
