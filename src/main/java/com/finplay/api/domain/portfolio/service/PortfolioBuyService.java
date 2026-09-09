package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PortfolioBuyService {

	private final HoldingRepository holdingRepository;
	private final HoldingLotRepository holdingLotRepository;

	public Holding applyBuyTrade(
		Account account,
		Instrument instrument,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal price,
		long fee,
		LocalDateTime now) {
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
			.orElseGet(() -> Holding.create(account, instrument, now));
		return applyBuyTrade(account, instrument, buyTrade, quantity, price, fee, now, holding);
	}

	public Holding applyBuyTrade(
		Account account,
		Instrument instrument,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal price,
		long fee,
		LocalDateTime now,
		Holding holding) {
		holding.applyBuy(quantity, price, now);
		holdingRepository.save(holding);

		HoldingLot holdingLot = HoldingLot.create(holding, buyTrade, quantity, price, fee, buyTrade.getExecutedAt(),
			now);
		holdingLotRepository.save(holdingLot);
		return holding;
	}

	public List<Holding> findExistingHoldingsForChunkUpdate(List<Long> accountIds, Long instrumentId) {
		return holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(accountIds, instrumentId);
	}
}
