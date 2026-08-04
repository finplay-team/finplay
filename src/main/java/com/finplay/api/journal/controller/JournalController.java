// 매수 체결에 대한 투자일기 작성 요청을 처리하는 컨트롤러
package com.finplay.api.journal.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.journal.dto.request.BuyJournalCreateRequest;
import com.finplay.api.journal.dto.request.SellJournalCreateRequest;
import com.finplay.api.journal.dto.request.SellJournalUpdateRequest;
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalResponse;
import com.finplay.api.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.journal.service.JournalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class JournalController {

	private final JournalService journalService;

	@PostMapping("/{buyTradeId}/journal")
	public ResponseEntity<BuyJournalResponse> createBuyJournal(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long buyTradeId,
		@Valid @RequestBody
		BuyJournalCreateRequest request) {
		BuyJournalResponse response = journalService.createBuyJournal(
			principal.userId(), buyTradeId, request.content());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/{sellTradeId}/sell-journal")
	public ResponseEntity<SellJournalResponse> createSellJournal(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long sellTradeId,
		@Valid @RequestBody
		SellJournalCreateRequest request) {
		SellJournalResponse response = journalService.createSellJournal(
			principal.userId(), sellTradeId, request.content());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PatchMapping("/{sellTradeId}/sell-journal")
	public ResponseEntity<SellJournalUpdateResponse> updateSellJournal(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long sellTradeId,
		@Valid @RequestBody
		SellJournalUpdateRequest request) {
		SellJournalUpdateResponse response = journalService.updateSellJournal(
			principal.userId(), sellTradeId, request.content());
		return ResponseEntity.ok(response);
	}
}
