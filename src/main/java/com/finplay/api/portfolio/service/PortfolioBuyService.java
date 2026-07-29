// 매수 체결 결과를 보유(holding)·매수 lot에 반영하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class PortfolioBuyService {

	private final HoldingRepository holdingRepository;
	private final HoldingLotRepository holdingLotRepository;

	public PortfolioBuyService(
		HoldingRepository holdingRepository, HoldingLotRepository holdingLotRepository) {
		this.holdingRepository = holdingRepository;
		this.holdingLotRepository = holdingLotRepository;
	}

	public void applyBuyTrade(
		Account account,
		Instrument instrument,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal price,
		long fee,
		LocalDateTime now) {
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseGet(() -> Holding.create(account, instrument, now));
		holding.applyBuy(quantity, price, now);
		holdingRepository.save(holding);

		HoldingLot holdingLot = HoldingLot.create(holding, buyTrade, quantity, price, fee, buyTrade.getExecutedAt(),
			now);
		holdingLotRepository.save(holdingLot);
	}
}
