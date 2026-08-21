// 게시물 첨부 이미지 업로드·다운로드의 형식·크기 검증과 저장 흐름을 검증하는 단위 테스트다.
package com.finplay.api.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class CommunityPostImageServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T03:04:05Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final CommunityPostImageRepository repository = Mockito.mock(CommunityPostImageRepository.class);
	private final FileStorageService fileStorageService = Mockito.mock(FileStorageService.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
	private final CommunityPostImageService service = new CommunityPostImageService(repository, fileStorageService,
		userQueryService, CLOCK, eventPublisher);

	@Test
	void uploadImageStoresFileAndSavesImageWhenFormatAndContentAreValid() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		when(userQueryService.getUser(42L)).thenReturn(uploader);
		MockMultipartFile file = new MockMultipartFile(
			"image", "photo.png", "image/png", "content".getBytes());
		when(repository.save(any(CommunityPostImage.class))).thenAnswer(invocation -> {
			CommunityPostImage image = invocation.getArgument(0);
			ReflectionTestUtils.setField(image, "id", 7L);
			return image;
		});

		CommunityPostImageResponse response = service.uploadImage(42L, file);

		ArgumentCaptor<CommunityPostImage> imageCaptor = ArgumentCaptor.forClass(CommunityPostImage.class);
		verify(repository).save(imageCaptor.capture());
		assertThat(imageCaptor.getValue().getUploader()).isSameAs(uploader);
		assertThat(imageCaptor.getValue().getOriginalFilename()).isEqualTo("photo.png");
		assertThat(imageCaptor.getValue().getContentType()).isEqualTo("image/png");
		assertThat(imageCaptor.getValue().getSizeBytes()).isEqualTo(file.getSize());
		assertThat(imageCaptor.getValue().getStoredFilename()).endsWith(".png");
		verify(fileStorageService).store(file, imageCaptor.getValue().getStoredFilename());
		assertThat(response.imageId()).isEqualTo(7L);
		assertThat(response.imageUrl()).isEqualTo("/api/community/posts/images/7/file");
	}

	@Test
	void uploadImageDerivesStoredFilenameExtensionFromContentTypeIgnoringMaliciousOriginalFilename() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		when(userQueryService.getUser(42L)).thenReturn(uploader);
		// 원본 파일명에 경로 구분자·상위 디렉터리 세그먼트를 넣어도 storedFilename은 contentType 기반
		// 확장자로만 결정돼야 한다 — 원본 파일명이 저장 경로 조립에 쓰이지 않음을 고정한다.
		MockMultipartFile file = new MockMultipartFile(
			"image", "a.b/../c", "image/png", "content".getBytes());
		when(repository.save(any(CommunityPostImage.class))).thenAnswer(invocation -> {
			CommunityPostImage image = invocation.getArgument(0);
			ReflectionTestUtils.setField(image, "id", 7L);
			return image;
		});

		service.uploadImage(42L, file);

		ArgumentCaptor<CommunityPostImage> imageCaptor = ArgumentCaptor.forClass(CommunityPostImage.class);
		verify(repository).save(imageCaptor.capture());
		assertThat(imageCaptor.getValue().getStoredFilename()).endsWith(".png").doesNotContain("/", "..");
		assertThat(imageCaptor.getValue().getOriginalFilename()).isEqualTo("a.b/../c");
	}

	@Test
	void uploadImageFailsWithValidationErrorAndDoesNotStoreOrSaveWhenContentTypeIsNotAllowed() {
		MockMultipartFile file = new MockMultipartFile(
			"image", "notes.txt", "text/plain", "content".getBytes());

		assertThatThrownBy(() -> service.uploadImage(42L, file))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(userQueryService);
		verifyNoInteractions(fileStorageService);
		verify(repository, never()).save(any());
	}

	@Test
	void uploadImageFailsWithValidationErrorAndDoesNotStoreOrSaveWhenFileIsEmpty() {
		MockMultipartFile file = new MockMultipartFile(
			"image", "empty.png", "image/png", new byte[0]);

		assertThatThrownBy(() -> service.uploadImage(42L, file))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(userQueryService);
		verifyNoInteractions(fileStorageService);
		verify(repository, never()).save(any());
	}

	@Test
	void uploadImageFailsWithValidationErrorWhenFileIsNull() {
		assertThatThrownBy(() -> service.uploadImage(42L, null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verifyNoInteractions(userQueryService);
		verifyNoInteractions(fileStorageService);
		verify(repository, never()).save(any());
	}

	@Test
	void loadImageFileReturnsResourceAndContentTypeWhenImageIsAssignedToAPost() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(uploader, "title", "content", null, LocalDateTime.now(CLOCK));
		image.assignToPost(post);
		when(repository.findById(7L)).thenReturn(Optional.of(image));
		Resource resource = Mockito.mock(Resource.class);
		when(fileStorageService.load("stored.png")).thenReturn(resource);

		CommunityPostImageFileResponse file = service.loadImageFile(999L, 7L);

		assertThat(file.resource()).isSameAs(resource);
		assertThat(file.contentType()).isEqualTo("image/png");
	}

	@Test
	void loadImageFileReturnsResourceWhenUnassignedImageIsRequestedByUploader() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		when(repository.findById(7L)).thenReturn(Optional.of(image));
		Resource resource = Mockito.mock(Resource.class);
		when(fileStorageService.load("stored.png")).thenReturn(resource);

		CommunityPostImageFileResponse file = service.loadImageFile(42L, 7L);

		assertThat(file.resource()).isSameAs(resource);
	}

	@Test
	void loadImageFileFailsWithNotFoundWhenUnassignedImageIsRequestedByAnotherUser() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		when(repository.findById(7L)).thenReturn(Optional.of(image));

		assertThatThrownBy(() -> service.loadImageFile(99L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(fileStorageService);
	}

	@Test
	void loadImageFileFailsWithNotFoundWhenImageDoesNotExist() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.loadImageFile(42L, 404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(fileStorageService);
	}

	@Test
	void deleteImageIfPresentDeletesRowAndPublishesFileDeletionEventWhenPostHasImage() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(
			uploader, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "image", image);

		service.deleteImageIfPresent(post);

		verify(repository).delete(image);
		verifyNoInteractions(fileStorageService);
		verify(eventPublisher).publishEvent(new CommunityPostImageDeletedEvent("stored.png"));
	}

	@Test
	void deleteImageIfPresentDoesNothingWhenPostHasNoImage() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));

		service.deleteImageIfPresent(post);

		verify(repository, never()).delete(any());
		verifyNoInteractions(fileStorageService);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void resolveImageForPostReturnsImageWhenOwnedByRequesterAndUnassigned() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(image, "id", 7L);
		when(repository.findById(7L)).thenReturn(Optional.of(image));

		CommunityPostImage resolved = service.resolveImageForPost(42L, 7L);

		assertThat(resolved).isSameAs(image);
	}

	@Test
	void resolveImageForPostFailsWithNotFoundWhenImageDoesNotExist() {
		when(repository.findById(7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resolveImageForPost(42L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	void resolveImageForPostFailsWithForbiddenWhenImageBelongsToAnotherUser() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(image, "id", 7L);
		when(repository.findById(7L)).thenReturn(Optional.of(image));

		assertThatThrownBy(() -> service.resolveImageForPost(99L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void resolveImageForPostFailsWithValidationErrorWhenImageAlreadyAssignedToAPost() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(uploader, "id", 42L);
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(image, "id", 7L);
		CommunityPost post = CommunityPost.create(uploader, "title", "content", null, LocalDateTime.now(CLOCK));
		image.assignToPost(post);
		when(repository.findById(7L)).thenReturn(Optional.of(image));

		assertThatThrownBy(() -> service.resolveImageForPost(42L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException)e).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}
}
