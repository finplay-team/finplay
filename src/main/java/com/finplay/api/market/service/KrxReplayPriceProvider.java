// StockPriceProvider의 공개 배포 기본 구현 — 내부적으로 StockReplayService(KRX 과거 데이터 재생)에 위임한다.
package com.finplay.api.market.service;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class KrxReplayPriceProvider implements StockPriceProvider {

	private final StockReplayService stockReplayService;

	@Override
	public StockMarketStatus getMarketStatus() {
		return stockReplayService.getMarketStatus();
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return stockReplayService.getCurrentPrice(instrumentId);
	}
}
