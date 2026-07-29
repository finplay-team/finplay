// KIS Open API 과거 1분봉 조회(REST)의 공통 계약 — 호출자는 인증 토큰 갱신·페이징(120건 상한 연속 호출)을 몰라야 한다.
package com.finplay.api.market.service;

import java.time.LocalDate;
import java.util.List;

public interface KisHistoricalCandleClient {

	// symbol 종목의 tradingDate 하루치(09:00~15:30 KST) 1분봉을 시각 오름차순으로 이어붙여 반환한다.
	List<RawMinuteCandle> fetchMinuteCandles(String symbol, LocalDate tradingDate);
}
