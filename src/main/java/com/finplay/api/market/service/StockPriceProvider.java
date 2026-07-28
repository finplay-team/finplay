// 주식 시세 공급자 공통 계약 — 구현체(KrxReplayPriceProvider·KisRealtimePriceProvider)가 무엇인지 상위 계층(PriceQueryService 등)에 노출하지 않는다.
package com.finplay.api.market.service;

public interface StockPriceProvider {

	StockMarketStatus getMarketStatus();

	StockReplayPriceDto getCurrentPrice(Long instrumentId);
}
