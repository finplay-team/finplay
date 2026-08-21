// KIS Open API 과거 일봉 조회(REST, 국내주식기간별시세)의 공통 계약 — 호출자는 인증 토큰 갱신·페이징(1회 100건 상한,
// 날짜 커서 역방향 연속 호출)을 몰라야 한다.
package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.List;

public interface KisDailyCandleClient {

	// symbol 종목의 [from, to] 구간(양끝 포함) 일봉을 거래일 오름차순으로 이어붙여 반환한다.
	List<RawDailyCandleDto> fetchDailyCandles(String symbol, LocalDate from, LocalDate to);
}
