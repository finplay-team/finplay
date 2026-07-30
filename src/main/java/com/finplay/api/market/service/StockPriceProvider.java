// 주식 시세 공급자 공통 계약 — 구현체(MVP는 KisHistoricalReplayPriceProvider 하나뿐)가 무엇인지 상위 계층(PriceQueryService 등)에 노출하지 않는다.
package com.finplay.api.market.service;

import java.time.LocalDateTime;
import java.util.List;

public interface StockPriceProvider {

	StockMarketStatus getMarketStatus();

	StockReplayPriceDto getCurrentPrice(Long instrumentId);

	// 계좌(=market) 단위로 여러 종목을 한 번에 평가할 때 종목과 무관한 전역 상태(현재시각·재생세션·시장상태)를 요청당 1회만
	// 계산하기 위한 배치 조회 (PR #97 리뷰 권장사항). 반환 순서는 instrumentIds 순서와 일치한다.
	List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds);

	// 아직 마감하지 않은 분봉은 포함하지 않는다 — from·to는 각각 선택이며 null이면 무제한(재생 중인 거래일 전체)으로 취급한다.
	List<StockCandleDto> getCandles(Long instrumentId, LocalDateTime from, LocalDateTime to);
}
