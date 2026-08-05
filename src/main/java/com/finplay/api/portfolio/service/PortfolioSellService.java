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

	// 지정가 매도 생성 시 holding을 잠그고 availableQuantity(=quantity-reservedQuantity) 기준으로 검증한다
	// (015-limit-order LMT-001). 다른 도메인 서비스가 HoldingRepository를 직접 주입하지 않게 한다(ADR-0002).
	public Holding getHoldingForUpdateOrThrow(Account account, Instrument instrument, BigDecimal requiredQuantity) {
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		if (holding.getAvailableQuantity().compareTo(requiredQuantity) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
		}
		return holding;
	}

	// 지정가 매도 체결 시 holding을 잠근다(015-limit-order LMT-002). 체결 대상 수량은 생성 시점에 이미
	// reserveQuantity로 예약이 확인된 값이라 availableQuantity 재검증은 하지 않는다 — 예약된 holding이 없는
	// 상태는 원장 불변식 위반이므로 방어적으로 IllegalStateException을 던진다(호출부 리스너가 건별 catch로 흡수).
	public Holding getHoldingForUpdate(Account account, Instrument instrument) {
		return holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseThrow(() -> new IllegalStateException(
				"체결 대상 holding을 찾을 수 없습니다. accountId=" + account.getId() + ", instrumentId=" + instrument.getId()));
	}

	// 매도 체결의 실현손익 계산·반영(시장가·지정가 공통, ORD-005 공식 그대로 재사용) — Trade.realizedPnl 확정 +
	// Account 현금·실현손익 갱신까지 원자적으로 수행한다. 이벤트 발행은 호출부 책임으로 남긴다.
	public long finalizeSellRealizedPnl(
		Account account, Trade sellTrade, long amount, long fee, SellAllocationDto allocation) {
		long realizedPnl = (amount - fee) - (allocation.totalAllocatedCost() + allocation.totalAllocatedBuyFee());
		sellTrade.fillRealizedPnl(realizedPnl);
		account.addCash(amount - fee);
		account.addRealizedPnl(realizedPnl);
		return realizedPnl;
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
