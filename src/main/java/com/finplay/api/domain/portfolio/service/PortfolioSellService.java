// 매도 체결 결과를 FIFO lot에 배분하고 보유(holding)에 반영하는 서비스
package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
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
	private final TutorialAccountService tutorialAccountService;

	// 지정가 매도 생성 시 holding을 잠그고 availableQuantity(=quantity-reservedQuantity) 기준으로 검증한다
	// (015-limit-order LMT-001). 다른 도메인 서비스가 HoldingRepository를 직접 주입하지 않게 한다(ADR-0002).
	// 016-investment-education-policy candidate 6: 기존 MARKET SELL(OrderExecutionService)도 이 메서드로
	// availableQuantity를 검증하므로, holding.getQuantity()(총 보유수량) 단일 값으로 매도 가능 여부를 판정하는
	// 지점은 이 서비스 안에 남아 있지 않다.
	public Holding getHoldingForUpdateOrThrow(Account account, Instrument instrument, BigDecimal requiredQuantity) {
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		if (holding.getAvailableQuantity().compareTo(requiredQuantity) < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
		}
		return holding;
	}

	// 지정가 매도 체결·취소·정정 시 holding을 잠근다(015-limit-order LMT-002). 체결 대상 수량은 생성 시점에 이미
	// reserveQuantity로 예약이 확인된 값이라 availableQuantity 재검증은 하지 않는다 — 예약된 holding이 없는
	// 상태는 원장 불변식 위반이므로 방어적으로 IllegalStateException을 던진다(호출부 리스너가 건별 catch로 흡수).
	public Holding getHoldingForUpdate(Account account, Instrument instrument) {
		return holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseThrow(() -> new IllegalStateException(
				"체결 대상 holding을 찾을 수 없습니다. accountId=" + account.getId() + ", instrumentId=" + instrument.getId()));
	}

	// OCO exit plan 생성(021-general-risk-management-oco) 시 holding을 잠근다. 호출부(ExitPlanCreationService)가
	// 이미 존재를 확인한 holding의 ID로 조회하는 동기 생성 경로이므로, 이 시점에 holding이 사라졌다면 예약 검증
	// 이전에 원장 불변식이 깨진 것이다 — availableQuantity 재검증은 별도로 validateAvailableQuantity에서
	// EXIT_PLAN_ALREADY_EXISTS 판정 이후에 수행하므로 여기서는 하지 않는다.
	public Holding getHoldingForUpdateForExitPlanCreation(Account account, Instrument instrument) {
		return holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseThrow(() -> new IllegalStateException(
				"exit plan 생성 대상 holding을 찾을 수 없습니다. accountId=" + account.getId()
					+ ", instrumentId=" + instrument.getId()));
	}

	// 매도 체결의 실현손익 계산·반영(시장가·지정가 공통, ORD-005 공식 그대로 재사용) — Trade.realizedPnl 확정 +
	// Account(또는 샌드박스면 TutorialAccount) 현금·실현손익 갱신까지 원자적으로 수행한다. 이 메서드 하나를
	// 시장가·지정가 매도 체결·재시작 보상매도·OCO 체결이 공유하므로, 여기 분기 하나만으로 네 경로 전부가
	// 튜토리얼 계좌 전환의 적용을 받는다(047 TUTORIAL-CASH-ISOL-003, plan.md "호출부 변경 지점" 2번).
	// 이벤트 발행은 호출부 책임으로 남긴다.
	public long finalizeSellRealizedPnl(
		Account account, Trade sellTrade, long amount, long fee, SellAllocationDto allocation, LocalDateTime now) {
		long realizedPnl = (amount - fee) - (allocation.totalAllocatedCost() + allocation.totalAllocatedBuyFee());
		// Trade.realizedPnl(체결 원장 값)은 033의 원칙대로 종목 종류와 무관하게 항상 채운다.
		sellTrade.fillRealizedPnl(realizedPnl);
		if (!sellTrade.getInstrument().isTutorialSample()) {
			account.addCash(amount - fee);
			// 샌드박스(튜토리얼) 종목 매도 손익은 계좌 집계(랭킹 score)에 반영하지 않는다(spec 033
			// SANDBOX-EXCL-004) — 이 분기는 실제 종목이므로 그대로 반영한다.
			account.addRealizedPnl(realizedPnl);
		} else {
			// 샌드박스(튜토리얼) 종목 매도는 실제 Account.cashBalance를 증가시키지 않는다(047
			// TUTORIAL-CASH-ISOL-003) — 대신 같은 사용자·시장의 튜토리얼 계좌 현금·realizedPnl을 같은
			// 트랜잭션에서 함께 갱신한다.
			TutorialAccount tutorialAccount = tutorialAccountService
				.getOrCreateForUpdate(account.getUser().getId(), account.getMarket(), now);
			tutorialAccount.addCash(amount - fee);
			tutorialAccount.addRealizedPnl(realizedPnl);
		}
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
