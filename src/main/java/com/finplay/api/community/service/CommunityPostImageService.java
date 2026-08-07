// 커뮤니티 게시물 첨부 이미지의 업로드·다운로드를 담당하는 서비스
package com.finplay.api.community.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.community.dto.response.CommunityPostImageFile;
import com.finplay.api.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.community.repository.CommunityPostImageRepository;
import com.finplay.api.community.storage.FileStorageService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class CommunityPostImageService {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

	private final CommunityPostImageRepository communityPostImageRepository;
	private final FileStorageService fileStorageService;
	private final UserQueryService userQueryService;
	private final Clock clock;

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
		String storedFilename = UUID.randomUUID() + resolveExtension(file.getOriginalFilename());
		fileStorageService.store(file, storedFilename);
		LocalDateTime now = LocalDateTime.now(clock);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, storedFilename, file.getOriginalFilename(), file.getContentType(), file.getSize(), now);
		return CommunityPostImageResponse.from(communityPostImageRepository.save(image));
	}

	@Transactional(readOnly = true)
	public CommunityPostImageFile loadImageFile(Long imageId) {
		CommunityPostImage image = communityPostImageRepository.findById(imageId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		return new CommunityPostImageFile(
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

	private String resolveExtension(String originalFilename) {
		if (originalFilename == null) {
			return "";
		}
		int dotIndex = originalFilename.lastIndexOf('.');
		return dotIndex >= 0 ? originalFilename.substring(dotIndex) : "";
	}
}
