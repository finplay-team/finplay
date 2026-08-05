// 특정 시점 T에 종목을 보유 중이던 회원 집단을 lot·배분으로 복원해 크기·매도까지 걸린 시간을 돌려주는 읽기 전용 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingQuantitySum;
import com.finplay.api.portfolio.repository.HoldingSellTime;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec 012 §C-6이 {@code portfolio}에 요구한 "특정 시점 보유자 집계 (회원 식별자 없는 반환)"다 — 집단 비교
 * (FEED-011)의 모집단 재구성을 담당한다. {@code feedback}은 이 서비스를 경유하고 {@code HoldingLotRepository}·
 * {@code TradeAllocationRepository}를 직접 주입하지 않는다(ADR-0002).
 *
 * <p><b>{@code holdings} 테이블을 쓰지 않는다.</b> 현재 상태만 갖는 가변 행이라 과거 시점 스냅샷이 없다 —
 * 이 서비스가 lot({@code holding_lots.original_quantity}, 불변)과 배분({@code trade_allocations.allocated_quantity},
 * 그 배분이 속한 매도 체결의 {@code executed_at} 기준)으로 시점 T의 순보유수량을 다시 계산하는 이유다
 * (spec.md §반사실·집단 비교 계산 [집단 비교]).
 *
 * <p><b>반환값은 회원 식별자를 포함하지 않는다.</b> 두 리포지토리 조회가 내부적으로 holding id로 묶어 순보유량을
 * 계산하지만, holding id는 이 클래스 밖으로 나가지 않고 집계된 크기·매도까지 걸린 분의 목록만 반환한다.
 */
@Service
@RequiredArgsConstructor
public class HolderPopulationQueryService {

	private final HoldingLotRepository holdingLotRepository;

	private final TradeAllocationRepository tradeAllocationRepository;

	/**
	 * 시점 {@code at}에 종목 {@code instrumentId}를 보유 중이던 회원 수(모집단 크기)를 센다.
	 *
	 * <p>회원 m이 시점 {@code at}에 보유 중 ⟺ (그 이전 매수 lot의 {@code original_quantity} 합) − (그 이전에
	 * 체결된 매도가 소비한 {@code allocated_quantity} 합) {@code > 0}이다. 시점 {@code at} 뒤에 전량 매도한
	 * 회원도 그 매도의 {@code executed_at}이 {@code at}보다 늦으면 이 합에서 빠지지 않으므로 모집단에 포함된다.
	 *
	 * @param instrumentId 종목 id
	 * @param at 카드 {@code windowEnd}를 그 카드가 재생된 서비스 날짜에 붙인 절대 시각 T
	 * @return 모집단 크기 (음수가 될 수 없다)
	 */
	@Transactional(readOnly = true)
	public int countHoldersAtTime(Long instrumentId, LocalDateTime at) {
		return holderIdsAtTime(instrumentId, at).size();
	}

	/**
	 * 시점 {@code at}(T) 모집단에 속한 holding들 중, T <b>뒤에</b> 처음 그 종목을 매도한 시각까지 걸린 분을
	 * 회원 식별자 없이(정수 리스트) 반환한다 (spec.md §반사실·집단 비교 계산 [집단 비교]).
	 *
	 * <p>매도 시각의 정본은 {@code trade_allocations.created_at}이 아니라 <b>그 배분이 속한 매도 체결의
	 * {@code executed_at}</b>이다 — {@code countHoldersAtTime}과 기준을 맞춘다. T 뒤에 (그때까지) 팔지 않은
	 * 회원은 "매도까지 걸린 시간"이 정의되지 않으므로 목록에서 빠진다 — 호출부가 이 목록의 크기와 모집단
	 * 크기의 차이로 미매도 인원을 유추하지 않는다(그 목적이라면 {@code countHoldersAtTime}과 별도로 판단한다).
	 *
	 * @param instrumentId 종목 id
	 * @param at 카드 {@code windowEnd}를 그 카드가 재생된 서비스 날짜에 붙인 절대 시각 T
	 * @return 매도까지 걸린 분의 목록 (순서는 의미가 없다). 모집단이 비거나 아무도 T 뒤에 팔지 않았으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<Integer> minutesToSellForHoldersAtTime(Long instrumentId, LocalDateTime at) {
		Set<Long> holderIds = holderIdsAtTime(instrumentId, at);
		if (holderIds.isEmpty()) {
			return List.of();
		}

		List<Integer> minutesToSell = new ArrayList<>();
		for (HoldingSellTime sellTime : tradeAllocationRepository
			.findFirstSellExecutedAtByHoldingForInstrumentAfter(instrumentId, at)) {
			if (holderIds.contains(sellTime.holdingId())) {
				minutesToSell.add((int)Duration.between(at, sellTime.firstSellExecutedAt()).toMinutes());
			}
		}
		return minutesToSell;
	}

	// 시점 at에 종목을 보유 중이던 holding id 집합을 계산한다 — countHoldersAtTime·minutesToSellForHoldersAtTime이
	// 공유하는 계산이다. holding id는 이 서비스 밖으로 나가지 않는다.
	private Set<Long> holderIdsAtTime(Long instrumentId, LocalDateTime at) {
		Map<Long, BigDecimal> boughtByHoldingId = new HashMap<>();
		for (HoldingQuantitySum sum : holdingLotRepository
			.sumOriginalQuantityByHoldingForInstrumentAtOrBefore(instrumentId, at)) {
			boughtByHoldingId.put(sum.holdingId(), sum.quantity());
		}

		Map<Long, BigDecimal> soldByHoldingId = new HashMap<>();
		for (HoldingQuantitySum sum : tradeAllocationRepository
			.sumAllocatedQuantityByHoldingForInstrumentAtOrBefore(instrumentId, at)) {
			soldByHoldingId.put(sum.holdingId(), sum.quantity());
		}

		Set<Long> holderIds = new HashSet<>();
		for (Map.Entry<Long, BigDecimal> entry : boughtByHoldingId.entrySet()) {
			BigDecimal sold = soldByHoldingId.getOrDefault(entry.getKey(), BigDecimal.ZERO);
			if (entry.getValue().subtract(sold).signum() > 0) {
				holderIds.add(entry.getKey());
			}
		}
		return holderIds;
	}
}
