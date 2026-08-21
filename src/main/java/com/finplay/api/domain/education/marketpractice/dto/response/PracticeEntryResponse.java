// 한 실행 세대의 진입 하나(기준선·매수·매도·"안 팔았다면")를 담는 완료 대조 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 041 SCENARIO-019b·021·021a, 042가 넘긴 진입별 대조 배열(이슈 #477 → #488).
 *
 * <p><b>왜 실행 전체 합만으로는 안 되는가.</b> 042가 재진입을 열면서 한 실행 세대에 매도가 둘 이상 생겼는데
 * {@code tradeResult}의 매도 시각·{@code sellCause}는 <b>첫 매도</b> 기준이다. 2막에서 손절당하고 3막에서
 * 익절한 사용자의 완료 화면에 손절 하나만 뜨는 것이 그 결과다 — <b>금액은 맞고 이야기가 틀린다</b>. 이
 * 배열이 그 결함을 닫는다.
 *
 * <p><b>진입 안에서는 첫 매도를 쓴다.</b> {@code sellAt}·{@code sellCause}가 그렇고, 이는 실행 전체
 * ({@code tradeResult})가 첫 매도를 쓰는 것과 같은 규칙을 진입 범위로 좁힌 것이다. 튜토리얼에서 한 진입에
 * 매도가 둘 이상 생기는 경로는 부분 수동 매도뿐이며, 그 경우에도 {@code sellPrice}는 수량 가중평균이고
 * {@code realizedPnl}은 합이라 금액은 전부 반영된다.
 *
 * @param entrySequence       실행 세대 안의 몇 번째 진입인가(1부터). 손절 후 재매수하면 2다
 * @param exitPreset          그 진입에 적용된 비율이 042 프리셋 3개 중 하나와 <b>정확히 같을 때</b> 그
 *                            식별자, 자유 조합이면 {@code null}(052). 042 이전 행은 기본 프리셋으로 해석해
 *                            내려보낸다
 * @param stopLossRate        그 진입에 적용된 손절률(퍼센트 수, 양수). {@code exitPreset}이 {@code null}일
 *                            수 있으므로 화면은 이 두 값으로 기준을 그린다 — 항상 채워진다
 * @param takeProfitRate      그 진입에 적용된 익절률(퍼센트 수, 양수)
 * @param buyOrderType        그 진입을 연 매수의 주문 유형({@code MARKET|LIMIT}, 이슈 #503). 진입 경계인
 *                            위험 snapshot의 매수 체결이 가리키는 주문에서 읽는다. <b>매도의 유형이
 *                            아니다</b> — 시장가로 산 포지션을 지정가로 팔거나 예약이 청산할 수 있어 둘이
 *                            같다는 보장이 없다. 완료 화면이 "이 진입은 시장가, 저 진입은 지정가"를
 *                            구분해 보여 주는 근거다
 * @param buyAt               그 진입의 최초 매수 체결 시각(기준선이 만들어진 시각)
 * @param buyPrice            그 진입 매수 체결의 수량 가중평균 단가, scale 8. 수수료를 포함하지 않는다
 * @param buyQuantity         그 진입의 매수 수량 합
 * @param stopLossPrice       그 진입의 손절 기준선
 * @param takeProfitPrice     그 진입의 익절 기준선
 * @param sellPrice           그 진입 매도 체결의 수량 가중평균 단가. 매도 전이면 {@code null}
 * @param sellQuantity        그 진입에서 <b>팔린</b> 수량 합. {@code realizedPnl}과
 *                            {@code unrealizedPnlIfHeld}가 <b>이 수량 기준</b>이라, 부분 매도한 진입에서
 *                            {@code buyQuantity}와 다르다 — 이 필드가 없으면 화면이 두 금액을 전체 수량의
 *                            것으로 오해한다
 * @param sellAt              그 진입의 최초 매도 체결 시각. 매도 전이면 {@code null}
 * @param sellCause           {@code STOP_LOSS|TAKE_PROFIT|MANUAL}. 매도 전이면 {@code null}
 * @param realizedPnl         그 진입 매도의 실현손익 합(원, 매수·매도 수수료가 모두 반영된 원장 값).
 *                            매도 전이면 {@code null}
 * @param unrealizedPnlIfHeld 그 포지션을 <b>팔지 않고 그대로 들고 있었다면</b>의 평가손익(원).
 *                            기준 가격은 같은 응답의 {@code priceAfterSell}이며 <b>매도 수수료를 뺀</b>
 *                            같은 기준이라 {@code realizedPnl}과 나란히 놓을 수 있다(SCENARIO-021a).
 *                            매도 전이거나 대본을 쓰지 않는 실행이면 {@code null}
 * @param scenarioScriptId    이 진입이 열릴 때 attempt가 쓰던 대본 식별자(049 ORDERBASICS-023). 대본을
 *                            쓰지 않는 실행(생성기 버전 1·legacy)이면 {@code null}. 대본을 쓰는 실행에서
 *                            스냅샷 컬럼이 비어 있으면(049 이전 진입) {@code CRYPTO_STORY_V1}로 해석한다.
 *                            전환({@code advance-script})이 같은 run 안에서 대본을 갈아끼우므로, 한 실행
 *                            세대 안에서도 진입마다 다른 값을 가질 수 있다 — 화면이 대본별로 끊어 그리는
 *                            근거다
 */
public record PracticeEntryResponse(
	int entrySequence,
	String exitPreset,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	String buyOrderType,
	LocalDateTime buyAt,
	BigDecimal buyPrice,
	BigDecimal buyQuantity,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	BigDecimal sellPrice,
	BigDecimal sellQuantity,
	LocalDateTime sellAt,
	String sellCause,
	Long realizedPnl,
	Long unrealizedPnlIfHeld,
	String scenarioScriptId) {
}
