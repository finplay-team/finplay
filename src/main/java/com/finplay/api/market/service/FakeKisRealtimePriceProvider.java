// 실제 KIS WebSocket 연결 없이 임의 체결 틱 주입·연결 끊김/재연결을 시뮬레이션하는 테스트 전용 StockPriceProvider 구현 (이슈 #82, MVP 범위 아님)
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 실제 KisRealtimePriceProvider와 같은 StockPriceProvider 계약을 구현해, PriceQueryService·SSE 등 소비 계층이
// 어느 구현체가 동작 중인지 몰라도 동일한 시나리오(끊김→가격 무효, 재연결→새 체결 후 복귀)를 단위 테스트로 검증할 수 있게 한다.
public class FakeKisRealtimePriceProvider implements StockPriceProvider {

	private final KisTickAggregator tickAggregator = new KisTickAggregator();
	private final Map<Long, TickSnapshot> latestTicks = new ConcurrentHashMap<>();
	private final Clock clock;

	private volatile boolean connected = true;
	private volatile StockMarketStatus marketStatus = StockMarketStatus.OPEN;

	public FakeKisRealtimePriceProvider(Clock clock) {
		this.clock = clock;
	}

	@Override
	public StockMarketStatus getMarketStatus() {
		return marketStatus;
	}

	// 테스트 전용: 시장상태를 직접 지정한다 — 실시간 공급자는 재생세션이 없어 시장 개폐를 스스로 판정하지 않으므로 테스트가 주입한다.
	// marketStatus는 틱 저장 시점이 아니라 조회 시점에 반영한다 — 장 마감 후에도 마지막 유효가격은 유지하되 marketStatus만 CLOSED로 보이게 하기 위함.
	public void setMarketStatus(StockMarketStatus marketStatus) {
		this.marketStatus = marketStatus;
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		TickSnapshot latest = latestTicks.get(instrumentId);
		if (latest == null) {
			return new StockReplayPriceDto(connected, marketStatus, null, null, null);
		}
		return new StockReplayPriceDto(connected, marketStatus, latest.sourceTradingDate(), latest.price(),
			latest.sourceTime());
	}

	@Override
	public List<StockCandleDto> getCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		return tickAggregator.getClosedCandles(instrumentId, from, to);
	}

	// 테스트 전용: 체결 틱을 주입한다. 연결이 끊긴 상태에서는 무시한다 — 끊김 중에는 마지막 가격으로 몰래 갱신하지 않는다(MKT-004 원칙).
	public void emitTick(Long instrumentId, BigDecimal price, LocalDateTime sourceTime, long volume) {
		if (!connected) {
			return;
		}
		latestTicks.put(instrumentId, new TickSnapshot(price, sourceTime, sourceTime.toLocalDate()));
		tickAggregator.onTick(instrumentId, sourceTime, price, volume);
	}

	// 테스트 전용: 연결 끊김을 시뮬레이션한다 — 모든 종목의 최신 가격을 무효화한다(재연결만으로는 복귀하지 않는다).
	public void simulateDisconnect() {
		connected = false;
		latestTicks.clear();
	}

	// 테스트 전용: 재연결을 시뮬레이션한다. 재연결 자체만으로는 가격이 복귀하지 않으며, 이후 emitTick으로 새 체결을 받아야 복귀한다.
	public void simulateReconnect() {
		connected = true;
	}

	public boolean isConnected() {
		return connected;
	}

	// 테스트에서 현재 시각이 필요할 때(예: emitTick의 sourceTime을 "지금"으로 주고 싶을 때) 재사용하도록 노출한다.
	public Clock getClock() {
		return clock;
	}

	private record TickSnapshot(BigDecimal price, LocalDateTime sourceTime, LocalDate sourceTradingDate) {
	}
}
