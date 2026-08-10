// 매도 회고 조립에 필요한 원장 컨텍스트 묶음 — 검증·배분 조회 트랜잭션이 조립 경로에 넘기는 중간값이다.
package com.finplay.api.feedback.service;

import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;

/**
 * {@link PostSellFeedbackContextReader}가 트랜잭션 A에서 읽어 {@link PostSellFeedbackReader}에게 돌려주는 묶음이다
 * (spec §FEED-012 결정 5).
 *
 * <p><b>응답 DTO가 아니라 조립 중간값이라 {@code dto/response/}에 두지 않는다</b> — {@link HoldExtremes}와 같은
 * 자리이며 §C-6에 이 이름이 없는 이유다.
 *
 * @param trade <b>트랜잭션 A가 끝난 뒤에도 읽히는 엔티티다.</b> {@code instrument}·{@code stockReplaySession}은
 *     {@code loadContext}가 {@code Hibernate.initialize}로 미리 채워 두므로, 이 record를 트랜잭션 밖에서 열어도
 *     {@code LazyInitializationException}이 나지 않는다 — 그 초기화를 빼면 이 record가 조용히 깨진다
 * @param allocation 스칼라·{@code LocalDate}/{@code LocalDateTime}/{@code BigDecimal}/{@code List<LocalDate>}만
 *     담아 엔티티 참조가 없다. 트랜잭션 밖으로 들고 나가도 안전하다(결정 5)
 */
record PostSellFeedbackContext(Trade trade, SellAllocationSummaryDto allocation) {
}
