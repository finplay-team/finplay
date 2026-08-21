// StockDailyCandleCollector가 종목 하나를 수집한 결과를 StockDailyCandleImportWriter와 공유하기 위한 값 객체
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import java.util.List;

// 종목 하나의 수집 결과 — failureReason이 null이면 구조 오류 없음(candles가 비어 있을 수도 있다: 채울 구간이
// 없어 건너뛴 경우도 정상 케이스로 취급한다, STOCK-DAILY-007).
record DailyInstrumentOutcome(Instrument instrument, List<StockDailyCandle> candles, String failureReason) {
	// 컬렉션 필드를 가진 record는 방어적 복사가 기본이다 (agent-mistakes.md 2026-07-29 — spotbugsMain EI_EXPOSE_REP).
	DailyInstrumentOutcome {
		candles = List.copyOf(candles);
	}
}
