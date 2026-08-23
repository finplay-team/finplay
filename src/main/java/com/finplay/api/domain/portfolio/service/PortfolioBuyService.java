// 매수 체결 결과를 보유(holding)·매수 lot에 반영하는 서비스
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

	// holdings row를 잠근 뒤 갱신한다(시장가·지정가 매수 체결 공통 호출부, 이슈 #224) — 신규 종목 첫 매수(row 없음)는
	// 호출부가 이미 잡은 account 락만으로 동시 생성 경합을 막는다(spec.md 확정된 설계 결정 10번, 방어적 유니크
	// 제약 catch 없음). 단건 호출부(시장가 매수·지정가 단건 체결)는 이 시그니처를 그대로 쓴다 — 내부에서
	// holding을 직접 조회·잠근 뒤 아래 오버로드로 위임한다(054-limit-order-fill-bulk-lock).
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

	// 호출부가 이미 잠갔거나(기존 holding) 아직 저장 전인 신규 Holding을 그대로 받아 저장한다(청크 벌크 락
	// 호출부, 054-limit-order-fill-bulk-lock) — 이 메서드 자체는 holdingRepository를 조회하지 않는다. 중복
	// SELECT를 피하는 것이 이 오버로드를 추가하는 이유다.
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

	// 지정가 체결 청크가 참조하는 계좌 목록 + 단일 종목으로 "이미 존재하는" holding을 한 번에 잠근다
	// (054-limit-order-fill-bulk-lock 호출부: LimitOrderFillService.fillBatch). 신규 생성(첫 매수) 대상은
	// 결과에 나타나지 않는다 — 호출부가 인메모리 맵으로 별도 처리한다. 다른 도메인 서비스가 HoldingRepository를
	// 직접 주입하지 않게 한다(ADR-0002).
	public List<Holding> findHoldingsForUpdate(List<Long> accountIds, Long instrumentId) {
		return holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(accountIds, instrumentId);
	}
}
