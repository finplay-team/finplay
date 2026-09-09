package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingQuantitySum;
import com.finplay.api.domain.portfolio.repository.HoldingSellTime;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
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

@Service
@RequiredArgsConstructor
public class HolderPopulationQueryService {

	private final HoldingLotRepository holdingLotRepository;

	private final TradeAllocationRepository tradeAllocationRepository;

	@Transactional(readOnly = true)
	public int countHoldersAtTime(Long instrumentId, LocalDateTime at) {
		return holderIdsAtTime(instrumentId, at).size();
	}

	@Transactional(readOnly = true)
	public List<Integer> minutesToSellForHoldersAtTime(Long instrumentId, LocalDateTime at) {
		return minutesToSellForHolderIds(instrumentId, at, holderIdsAtTime(instrumentId, at));
	}

	@Transactional(readOnly = true)
	public PopulationSnapshot populationSnapshotAtTime(Long instrumentId, LocalDateTime at) {
		Set<Long> holderIds = holderIdsAtTime(instrumentId, at);
		return new PopulationSnapshot(holderIds.size(), minutesToSellForHolderIds(instrumentId, at, holderIds));
	}

	public record PopulationSnapshot(int holderCount, List<Integer> minutesToSell) {

		public PopulationSnapshot {
			minutesToSell = List.copyOf(minutesToSell);
		}
	}

	private List<Integer> minutesToSellForHolderIds(Long instrumentId, LocalDateTime at, Set<Long> holderIds) {
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
