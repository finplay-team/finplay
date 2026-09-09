package com.finplay.api.domain.community.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.community.dto.request.CommunityPostCreateRequest;
import com.finplay.api.domain.community.dto.request.CommunityPostUpdateRequest;
import com.finplay.api.domain.community.dto.response.CommunityPostListResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostResponse;
import com.finplay.api.domain.community.service.CommunityPostService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/posts")
@RequiredArgsConstructor
public class CommunityPostController {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 10;
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 50;
	private static final String SORT_LATEST = "latest";
	private static final String SORT_POPULAR = "popular";

	private final CommunityPostService communityPostService;

	@GetMapping("/{postId}")
	public ResponseEntity<CommunityPostResponse> getPost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long postId) {
		return ResponseEntity.ok(communityPostService.getPost(postId, principal.userId()));
	}

	@PostMapping
	public ResponseEntity<CommunityPostResponse> createPost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		CommunityPostCreateRequest request) {
		CommunityPostResponse response = communityPostService.createPost(principal.userId(), request.title(),
			request.content(), request.instrumentId(), request.imageId(), request.sharedTradeId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PatchMapping("/{postId}")
	public ResponseEntity<CommunityPostResponse> updatePost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long postId,
		@Valid @RequestBody
		CommunityPostUpdateRequest request) {
		CommunityPostResponse response = communityPostService.updatePost(principal.userId(), postId, request.title(),
			request.content(), request.instrumentIdProvided(), request.instrumentId());
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{postId}")
	public ResponseEntity<Void> deletePost(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long postId) {
		communityPostService.deletePost(principal.userId(), postId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping
	public ResponseEntity<CommunityPostListResponse> getPosts(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam(defaultValue = "" + DEFAULT_PAGE)
		int page,
		@RequestParam(defaultValue = "" + DEFAULT_SIZE)
		int size,
		@RequestParam(required = false)
		Long instrumentId,
		@RequestParam(defaultValue = SORT_LATEST)
		String sort) {
		validatePageAndSize(page, size);
		validateSort(sort);
		CommunityPostListResponse response = communityPostService.getPosts(
			page, size, instrumentId, sort, principal.userId());
		return ResponseEntity.ok(response);
	}

	private void validatePageAndSize(int page, int size) {
		if (page < 0 || size < MIN_SIZE || size > MAX_SIZE) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "page는 0 이상, size는 1~50 사이여야 합니다.");
		}
	}

	private void validateSort(String sort) {
		if (!SORT_LATEST.equals(sort) && !SORT_POPULAR.equals(sort)) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "sort는 latest 또는 popular만 지정할 수 있습니다.");
		}
	}
}
