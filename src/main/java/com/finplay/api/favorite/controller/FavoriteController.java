// 인증 사용자의 즐겨찾기 등록 요청을 받아 생성 결과를 반환하는 컨트롤러
package com.finplay.api.favorite.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.favorite.dto.request.FavoriteCreateRequest;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

	private final FavoriteService favoriteService;

	@PostMapping
	public ResponseEntity<FavoriteResponse> createFavorite(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		FavoriteCreateRequest request) {
		FavoriteResponse response = favoriteService.createFavorite(principal.userId(), request.instrumentId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
