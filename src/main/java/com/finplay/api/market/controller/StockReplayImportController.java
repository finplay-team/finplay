// 실제 KIS 분봉 수집을 즉시 트리거하는 로컬 개발용 컨트롤러 (local 프로필에서만 빈으로 등록된다)
package com.finplay.api.market.controller;

import com.finplay.api.market.dto.response.StockReplayImportTriggerResponse;
import com.finplay.api.market.service.StockReplayImportTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/stock-replay-imports")
@Profile("local")
@RequiredArgsConstructor
public class StockReplayImportController {

	private final StockReplayImportTriggerService stockReplayImportTriggerService;

	// 요청 본문이 없다 — 대상 거래일은 수집 배치와 동일하게 직전 영업일로 고정된다(배치와 다른 날짜를 쓰면
	// 재생세션 확정 로직과 어긋난다). 16종 순차 KIS 호출이라 수십 초가 걸릴 수 있다.
	@PostMapping
	public ResponseEntity<StockReplayImportTriggerResponse> createStockReplayImport() {
		return ResponseEntity.ok(stockReplayImportTriggerService.trigger());
	}
}
