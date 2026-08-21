// 커뮤니티 게시물 첨부 이미지의 업로드·다운로드를 담당하는 서비스
package com.finplay.api.domain.community.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostImage;
import com.finplay.api.domain.community.dto.response.CommunityPostImageFileResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.domain.community.event.CommunityPostImageDeletedEvent;
import com.finplay.api.domain.community.repository.CommunityPostImageRepository;
import com.finplay.api.domain.community.storage.FileStorageService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class CommunityPostImageService {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	// 확장자는 클라이언트가 보낸 원본 파일명이 아니라 서버가 검증한 contentType에서만 결정한다 — 원본
	// 파일명을 그대로 쓰면 그 값이 저장 경로 조립에 들어가 경로 조작 여지가 생긴다(PR #269 리뷰).
	private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
		"image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");

	private final CommunityPostImageRepository communityPostImageRepository;
	private final FileStorageService fileStorageService;
	private final UserQueryService userQueryService;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public CommunityPostImageResponse uploadImage(Long authenticatedUserId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "첨부할 이미지 파일이 없습니다.");
		}
		if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "허용하지 않는 이미지 형식입니다. JPEG, PNG, WEBP만 첨부할 수 있습니다.");
		}
		User uploader = userQueryService.getUser(authenticatedUserId);
		String storedFilename = UUID.randomUUID() + EXTENSION_BY_CONTENT_TYPE.get(file.getContentType());
		fileStorageService.store(file, storedFilename);
		LocalDateTime now = LocalDateTime.now(clock);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, storedFilename, file.getOriginalFilename(), file.getContentType(), file.getSize(), now);
		return CommunityPostImageResponse.from(communityPostImageRepository.save(image));
	}

	@Transactional(readOnly = true)
	public CommunityPostImageFileResponse loadImageFile(Long authenticatedUserId, Long imageId) {
		CommunityPostImage image = communityPostImageRepository.findById(imageId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		// 게시물에 연결된(공개) 이미지는 누구나 볼 수 있지만, 아직 게시되지 않은(post_id IS NULL) 이미지는
		// 업로더 본인만 볼 수 있다 — 존재를 숨기기 위해 403이 아니라 404로 거부한다(PR #269 리뷰).
		if (!image.isAssigned() && !image.getUploader().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		return new CommunityPostImageFileResponse(
			fileStorageService.load(image.getStoredFilename()), image.getContentType());
	}

	@Transactional(readOnly = true)
	public CommunityPostImage resolveImageForPost(Long authenticatedUserId, Long imageId) {
		CommunityPostImage image = communityPostImageRepository.findById(imageId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		if (!image.getUploader().getId().equals(authenticatedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 업로드한 이미지만 사용할 수 있습니다.");
		}
		if (image.isAssigned()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이미 다른 게시물에 사용된 이미지입니다.");
		}
		return image;
	}

	@Transactional
	public void deleteImageIfPresent(CommunityPost post) {
		CommunityPostImage image = post.getImage();
		if (image == null) {
			return;
		}
		String storedFilename = image.getStoredFilename();
		communityPostImageRepository.delete(image);
		// 물리 파일 삭제는 되돌릴 수 없으므로 이 DB 트랜잭션이 실제로 커밋된 뒤에만 수행한다 — 여기서 바로
		// 지우면 이후 롤백 시 DB 행은 살아있고 파일만 사라진 상태가 된다(PR #269 리뷰).
		eventPublisher.publishEvent(new CommunityPostImageDeletedEvent(storedFilename));
	}
}
