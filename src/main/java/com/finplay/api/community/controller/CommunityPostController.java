// 커뮤니티 게시글 생성 요청을 검증하고 인증 사용자 ID로 서비스에 전달하는 컨트롤러
package com.finplay.api.community.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.community.dto.request.CommunityPostCreateRequest;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.service.CommunityPostService;
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
@RequestMapping("/api/community/posts")
@RequiredArgsConstructor
public class CommunityPostController {

	private final CommunityPostService communityPostService;

	@PostMapping
	public ResponseEntity<CommunityPostResponse> createPost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		CommunityPostCreateRequest request) {
		CommunityPostResponse response = communityPostService.createPost(principal.userId(), request.title(),
			request.content());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
