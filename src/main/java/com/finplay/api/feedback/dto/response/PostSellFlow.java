// 매도 이후 그 거래일 마지막 분봉까지의 가격 흐름 — 장 마감 게이트를 통과한 뒤에만 값이 채워진다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절, 게이트는 spec §C-5, 계산식은
 * §파생 사실 계산이다.
 *
 * <p><b>게이트 전에는 {@code status = NOT_YET}이고 가격 필드가 전부 {@code null}이다.</b> 14:40에 매도하고
 * 14:41에 조회하면 장 마감까지의 가격은 아직 재생되지 않은 미래이고, 그걸 보여주면 같은 종목을 재매수할 때
 * 답을 아는 상태가 된다. <b>기준 날짜는 "오늘"이 아니라 그 매도 체결의 서비스 날짜다.</b>
 *
 * <p>{@code closeAt}은 <b>그 거래일 "마지막 분봉"</b>의 시각이다 — {@code 15:30}을 리터럴로 찾으면 그 분봉이
 * 없는 날 예외 없이 {@code null}이 된다(§C-2-1).
 *
 * <p>판정과 값 전체를 채우는 것은 이 이슈의 3번 항목이다.
 */
public record PostSellFlow(
	PostSellFeedbackStatus status,
	BigDecimal closePrice,
	LocalDateTime closeAt,
	BigDecimal sellToCloseRate,
	BigDecimal postSellHighPrice,
	LocalDateTime postSellHighAt) {
}
