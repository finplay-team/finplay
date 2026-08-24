// 커뮤니티 게시물 첨부 이미지의 업로드·다운로드 요청을 처리하는 컨트롤러
package com.finplay.api.domain.community.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.community.dto.response.CommunityPostImageFileResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.domain.community.service.CommunityPostImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/community/posts/images")
@RequiredArgsConstructor
public class CommunityPostImageController {

	private final CommunityPostImageService communityPostImageService;

	@PostMapping
	public ResponseEntity<CommunityPostImageResponse> uploadImage(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam("image")
		MultipartFile image) {
		CommunityPostImageResponse response = communityPostImageService.uploadImage(principal.userId(), image);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{imageId}/file")
	public ResponseEntity<Resource> getImageFile(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long imageId) {
		CommunityPostImageFileResponse file = communityPostImageService.loadImageFile(principal.userId(), imageId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.contentType()))
			.header("X-Content-Type-Options", "nosniff")
			.body(file.resource());
	}
}
