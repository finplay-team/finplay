// 매도 체결 1건의 FIFO 배분 요약 — 가중평균 매수단가·배분 원가·가장 이른 매수 시각과 lot별 원본 거래일을 담는다.
package com.finplay.api.domain.portfolio.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code feedback}의 매도 회고(spec 012 FEED-007)가 원장 수치를 읽는 경로다. {@code trade_allocations}·
 * {@code holding_lots}는 {@code portfolio} 소유라 다른 도메인이 repository를 직접 주입하지 않고 서비스를
 * 경유한다({@code docs/conventions/code.md}, ADR-0002).
 *
 * <p><b>판정을 여기서 하지 않는다.</b> {@code buySourceTradingDates}를 그대로 돌려주고
 * "하나라도 매도 체결과 다르면 {@code sameSessionCompleted=false}"라는 규칙은 {@code feedback}이 갖는다 —
 * 그 규칙은 회고 응답의 형태를 가르는 피드백 도메인의 규칙이고, {@code portfolio}는 배분 사실만 안다.
 *
 * @param buyPrice              FIFO 배분 가중평균 매수단가 = {@code allocatedCost ÷ allocatedQuantity}.
 *                              scale 8 {@code HALF_UP}으로 {@code Holding.averagePrice}와 같은 정밀도다
 * @param earliestBuyAt         배분된 lot 중 <b>가장 이른</b> {@code executed_at}. 한 매도가 여러 lot에
 *                              배분되므로 "매수 시각"이 단일하지 않다(FEED-007)
 * @param earliestBuySourceTradingDate {@code earliestBuyAt} lot의 매수 체결이 발생한 재생세션의 원본 거래일.
 *                              코인 체결은 재생세션이 없어 {@code null}이다
 * @param allocatedCost         배분된 매수원가 합 ({@code trades.amount} 기준, 원 단위)
 * @param allocatedBuyFee       배분된 매수수수료 합
 * @param allocatedQuantity     배분된 수량 합 (= 매도 수량)
 * @param buySourceTradingDates 배분된 lot의 매수 체결별 원본 거래일. <b>중복을 제거하지 않고 배분 순서를
 *                              유지한다</b> — 호출부가 개수가 아니라 "전부 같은가"만 보므로 중복 제거가 판정을
 *                              바꾸지 않고, 순서를 유지하면 첫 원소가 {@code earliestBuySourceTradingDate}와
 *                              같아 대조가 쉽다
 */
public record SellAllocationSummaryDto(
	BigDecimal buyPrice,
	LocalDateTime earliestBuyAt,
	LocalDate earliestBuySourceTradingDate,
	long allocatedCost,
	long allocatedBuyFee,
	BigDecimal allocatedQuantity,
	List<LocalDate> buySourceTradingDates) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29). 원본 거래일이 null인 lot(코인)도 담기므로 List.copyOf를 쓸 수 없다.
	public SellAllocationSummaryDto {
		buySourceTradingDates = Collections.unmodifiableList(new ArrayList<>(buySourceTradingDates));
	}
}
