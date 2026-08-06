// 인증 사용자의 관심목록 등록·조회·해제 요청을 처리하는 컨트롤러
package com.finplay.api.watchlist.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.market.domain.Market;
import com.finplay.api.watchlist.dto.request.WatchlistItemCreateRequest;
import com.finplay.api.watchlist.dto.response.WatchlistItemListResponse;
import com.finplay.api.watchlist.dto.response.WatchlistItemResponse;
import com.finplay.api.watchlist.service.WatchlistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/watchlist-items")
@RequiredArgsConstructor
@Validated
public class WatchlistController {

	private final WatchlistService watchlistService;

	@PostMapping
	public ResponseEntity<WatchlistItemResponse> createWatchlistItem(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		WatchlistItemCreateRequest request) {
		WatchlistItemResponse response = watchlistService.createWatchlistItem(principal.userId(),
			request.instrumentId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	public ResponseEntity<WatchlistItemListResponse> getWatchlistItems(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam(required = false)
		Market market) {
		return ResponseEntity.ok(watchlistService.getWatchlistItems(principal.userId(), market));
	}

	@DeleteMapping("/{instrumentId}")
	public ResponseEntity<Void> deleteWatchlistItem(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable @Positive(message = "종목 ID는 양수여야 합니다.")
		Long instrumentId) {
		watchlistService.deleteWatchlistItem(principal.userId(), instrumentId);
		return ResponseEntity.noContent().build();
	}
}
