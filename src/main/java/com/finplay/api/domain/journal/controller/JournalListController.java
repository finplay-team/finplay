// 본인의 시장별 투자일기(매수·매도 회고 병합) 목록을 커서 페이지네이션으로 조회하는 컨트롤러
package com.finplay.api.domain.journal.controller;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.journal.dto.response.JournalListResponse;
import com.finplay.api.domain.journal.service.JournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalListController {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 100;

	private final JournalService journalService;

	@GetMapping
	public ResponseEntity<JournalListResponse> getMyJournalEntries(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market,
		@RequestParam(required = false)
		String cursor,
		@RequestParam(defaultValue = "" + DEFAULT_LIMIT)
		int limit) {
		validateLimit(limit);
		return ResponseEntity.ok(journalService.getMyJournalEntries(principal.userId(), market, cursor, limit));
	}

	private void validateLimit(int limit) {
		if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~100 사이여야 합니다.");
		}
	}
}
