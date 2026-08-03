// 코인 1분봉 조회 공통 계약 — 구현체(BithumbRestCandleProvider·FakeCryptoCandleProvider)가 무엇인지 CandleQueryService에 노출하지 않는다.
package com.finplay.api.market.service;

import java.time.LocalDateTime;
import java.util.List;

public interface CryptoCandleProvider {

	// 진행 중인(아직 마감하지 않은) 분봉도 포함해 시각 오름차순으로 반환한다 — 주식과 반대(MKT-008). from·to는 각각 선택이며
	// null이면 무제한(최신부터 직전으로 최대 200개)으로 취급한다. 저장·캐시하지 않으므로 호출마다 외부 조회가 발생한다.
	// interval(1m·1d·1w·1M)에 따라 빗썸의 minutes/1·days·weeks·months 엔드포인트로 위임한다(이슈 #143).
	List<CryptoCandleDto> getCandles(String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to);
}
