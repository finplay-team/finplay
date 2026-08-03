// 인증 사용자의 즐겨찾기 조회·등록·해제 요청을 처리하는 컨트롤러
package com.finplay.api.favorite.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.favorite.dto.request.FavoriteCreateRequest;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Validated
public class FavoriteController {

	private final FavoriteService favoriteService;

	@GetMapping
	public ResponseEntity<FavoriteListResponse> getFavorites(
		@AuthenticationPrincipal
		AuthenticatedUser principal) {
		return ResponseEntity.ok(favoriteService.getFavorites(principal.userId()));
	}

	@PostMapping
	public ResponseEntity<FavoriteResponse> createFavorite(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		FavoriteCreateRequest request) {
		FavoriteResponse response = favoriteService.createFavorite(principal.userId(), request.instrumentId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@DeleteMapping("/{instrumentId}")
	public ResponseEntity<Void> deleteFavorite(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable @Positive(message = "종목 ID는 양수여야 합니다.")
		Long instrumentId) {
		favoriteService.deleteFavorite(principal.userId(), instrumentId);
		return ResponseEntity.noContent().build();
	}
}
