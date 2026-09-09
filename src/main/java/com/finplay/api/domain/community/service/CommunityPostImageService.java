package com.finplay.api.domain.community.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.community.dto.response.CommunityPostImageFileResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostImage;
import com.finplay.api.domain.community.event.CommunityPostImageDeletedEvent;
import com.finplay.api.domain.community.repository.CommunityPostImageRepository;
import com.finplay.api.domain.community.storage.FileStorageService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
		eventPublisher.publishEvent(new CommunityPostImageDeletedEvent(storedFilename));
	}
}
