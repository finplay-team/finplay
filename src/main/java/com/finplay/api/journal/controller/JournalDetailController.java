// 본인 소유 체결에 달린 투자일기 1건을 매수·매도 타입별 경로로 단건 조회하는 컨트롤러
package com.finplay.api.journal.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.journal.dto.response.BuyJournalDetailResponse;
import com.finplay.api.journal.dto.response.SellJournalDetailResponse;
import com.finplay.api.journal.service.JournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalDetailController {

	private final JournalService journalService;

	@GetMapping("/buy/{buyTradeId}")
	public ResponseEntity<BuyJournalDetailResponse> getBuyJournal(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long buyTradeId) {
		return ResponseEntity.ok(journalService.getBuyJournal(principal.userId(), buyTradeId));
	}

	@GetMapping("/sell/{sellTradeId}")
	public ResponseEntity<SellJournalDetailResponse> getSellJournal(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long sellTradeId) {
		return ResponseEntity.ok(journalService.getSellJournal(principal.userId(), sellTradeId));
	}
}
