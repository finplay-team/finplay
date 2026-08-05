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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PortfolioBuyService {

	private final HoldingRepository holdingRepository;
	private final HoldingLotRepository holdingLotRepository;

	// holdings row를 잠근 뒤 갱신한다(시장가·지정가 매수 체결 공통 호출부, 이슈 #224) — 신규 종목 첫 매수(row 없음)는
	// 호출부가 이미 잡은 account 락만으로 동시 생성 경합을 막는다(spec.md 확정된 설계 결정 10번, 방어적 유니크
	// 제약 catch 없음).
	public void applyBuyTrade(
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
		holding.applyBuy(quantity, price, now);
		holdingRepository.save(holding);

		HoldingLot holdingLot = HoldingLot.create(holding, buyTrade, quantity, price, fee, buyTrade.getExecutedAt(),
			now);
		holdingLotRepository.save(holdingLot);
	}
}
