// 튜토리얼 이번 실행의 매매 결과(매수·매도 체결가, 실현손익, 수익률, 매도 위치 판정)를 담는 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;

/**
 * 이슈 #421. 실습을 끝낸 사용자가 "얼마를 벌었는지"를 화면에서 알 수 없던 문제를 메운다. 매수 뒤부터
 * {@code buyPrice}가 채워지고 매도 체결이 생겨야 나머지 네 필드가 채워진다 — 매도 전에는 전부
 * {@code null}이다.
 *
 * <p><b>수수료 기준을 섞지 않는다.</b> {@code buyPrice}·{@code sellPrice}는 체결 <b>단가</b>라 수수료를
 * 포함하지 않고, {@code realizedPnl}·{@code returnRate}는 매수·매도 수수료가 <b>모두 반영된</b>
 * 순손익 기준이다({@code trades.realized_pnl} 원장 값). 따라서
 * {@code (sellPrice − buyPrice) × 수량}은 {@code realizedPnl}과 수수료만큼 다르며, 이 차이를 없애려고
 * 클라이언트가 단가로 손익을 다시 계산하면 서버 원장과 어긋난다.
 *
 * @param buyPrice    현재 실행 FILLED BUY의 수량 가중평균 체결가, scale 8. 매수 전이면 {@code null}
 * @param sellPrice   현재 실행 FILLED SELL의 수량 가중평균 체결가, scale 8. 매도 전이면 {@code null}
 * @param realizedPnl 현재 실행 매도의 실현손익 합(원, 수수료 포함 기준). 매도 전이면 {@code null}
 * @param returnRate  {@code realizedPnl ÷ (배분 매수원가 + 배분 매수수수료)}, scale 4 {@code HALF_UP}.
 *                    매도 직후 피드백(spec 012)의 {@code returnRate}와 같은 식·같은 정밀도다
 * @param sellVerdict {@code ABOVE_TAKE_PROFIT|BELOW_STOP_LOSS|BETWEEN_LINES} 또는 매도 전·기준선 부재 시
 *                    {@code null}
 * @param sellCause   {@code STOP_LOSS|TAKE_PROFIT|MANUAL}. 매도 전이면 {@code null} (042 EXITPRESET-008).
 *                    {@code sellVerdict}와 다르다 — 자동 예약 체결이면 verdict가 정의상 항상 경계값이라
 *                    그것만으로는 "예약이 팔았는지 내가 팔았는지"를 알 수 없다
 */
public record PracticeTradeResultResponse(
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	Long realizedPnl,
	BigDecimal returnRate,
	String sellVerdict,
	String sellCause) {
}
