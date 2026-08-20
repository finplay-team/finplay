// 실제 KIS 일봉 아카이브 수집을 즉시 트리거하는 로컬 개발용 컨트롤러 (local 프로필에서만 빈으로 등록된다)
package com.finplay.api.market.controller;

import com.finplay.api.market.dto.response.StockDailyImportTriggerResponse;
import com.finplay.api.market.service.StockDailyImportTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dev/stock-daily-imports")
@Profile("local")
@RequiredArgsConstructor
public class StockDailyImportTriggerController {

	private final StockDailyImportTriggerService stockDailyImportTriggerService;

	// 요청 본문이 없다 — 대상 구간은 배치와 동일하게 종목별 "있어야 할 구간 − 이미 저장된 구간"으로 자동 계산된다.
	// 최초 호출은 종목별 최대 3년치를 순차 조회하므로 수 분 이상 걸릴 수 있다.
	@PostMapping
	public ResponseEntity<StockDailyImportTriggerResponse> createStockDailyImport() {
		return ResponseEntity.ok(stockDailyImportTriggerService.trigger());
	}
}
