// 매도 체결 결과를 FIFO lot에 배분하고 보유(holding)에 반영하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PortfolioSellService {

	private final HoldingRepository holdingRepository;
	private final HoldingLotRepository holdingLotRepository;
	private final TradeAllocationRepository tradeAllocationRepository;

	public Holding getHoldingOrThrow(Account account, Instrument instrument, BigDecimal requiredQuantity) {
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		if (holding.getQuantity().compareTo(requiredQuantity) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
		}
		return holding;
	}

	public SellAllocationDto applySellTrade(
		Holding holding, Trade sellTrade, BigDecimal sellQuantity, LocalDateTime now) {
		List<HoldingLot> lots = holdingLotRepository
			.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
				holding.getId(), BigDecimal.ZERO);

		BigDecimal remainingToAllocate = sellQuantity;
		long totalAllocatedCost = 0L;
		long totalAllocatedBuyFee = 0L;

		for (HoldingLot lot : lots) {
			if (remainingToAllocate.signum() <= 0) {
				break;
			}

			BigDecimal allocatedQuantity = remainingToAllocate.min(lot.getRemainingQuantity());
			boolean isLastAllocationForLot = allocatedQuantity.compareTo(lot.getRemainingQuantity()) == 0;

			long allocatedCost;
			long allocatedBuyFee;
			if (isLastAllocationForLot) {
				long previousCost = tradeAllocationRepository.sumAllocatedCostByHoldingLotId(lot.getId());
				long previousBuyFee = tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(lot.getId());
				allocatedCost = lot.getBuyTrade().getAmount() - previousCost;
				allocatedBuyFee = lot.getBuyFee() - previousBuyFee;
			} else {
				allocatedCost = lot.getUnitCost()
					.multiply(allocatedQuantity)
					.setScale(0, RoundingMode.FLOOR)
					.longValueExact();
				allocatedBuyFee = BigDecimal.valueOf(lot.getBuyFee())
					.multiply(allocatedQuantity)
					.divide(lot.getOriginalQuantity(), 0, RoundingMode.FLOOR)
					.longValueExact();
			}

			lot.consume(allocatedQuantity);
			holdingLotRepository.save(lot);

			tradeAllocationRepository.save(
				TradeAllocation.create(sellTrade, lot, allocatedQuantity, allocatedCost, allocatedBuyFee, now));

			totalAllocatedCost += allocatedCost;
			totalAllocatedBuyFee += allocatedBuyFee;
			remainingToAllocate = remainingToAllocate.subtract(allocatedQuantity);
		}

		if (remainingToAllocate.signum() > 0) {
			throw new IllegalStateException("보유 lot 잔여수량 합계가 holding 보유수량과 일치하지 않습니다.");
		}

		holding.applySell(sellQuantity, now);
		holdingRepository.save(holding);

		return new SellAllocationDto(totalAllocatedCost, totalAllocatedBuyFee);
	}
}
