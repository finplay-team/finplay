// KisHistoricalCandleCollector가 종목 하나를 수집한 결과를 KisHistoricalCandleImportWriter와 공유하기 위한 값 객체
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.StockCandle;
import java.util.List;

// 종목 하나의 수집 결과 — failureReason이 null이면 구조 오류 없음(candles가 비어 있을 수도 있다: 이미 저장돼 있어
// 건너뛴 경우, 또는 실제로 그날 분봉이 없는 경우 모두 정상 케이스로 취급한다).
record InstrumentOutcome(Instrument instrument, List<StockCandle> candles, String failureReason) {
	// 컬렉션 필드를 가진 record는 방어적 복사가 기본이다 (agent-mistakes.md 2026-07-29 — spotbugsMain EI_EXPOSE_REP).
	InstrumentOutcome {
		candles = List.copyOf(candles);
	}
}
