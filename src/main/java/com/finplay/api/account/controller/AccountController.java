// 계좌 요약 조회를 담당하는 컨트롤러
package com.finplay.api.account.controller;

import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.token.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

	private final AccountService accountService;

	@GetMapping("/summary")
	public ResponseEntity<AccountSummaryResponse> getAccountSummary(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam
		Market market) {
		return ResponseEntity.ok(accountService.getAccountSummary(principal.userId(), market));
	}
}
