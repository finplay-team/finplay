// 게시물 첨부 이미지 업로드·다운로드의 형식·크기 검증과 저장 흐름을 검증하는 단위 테스트다.
package com.finplay.api.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.community.dto.response.CommunityPostImageFile;
import com.finplay.api.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.community.repository.CommunityPostImageRepository;
import com.finplay.api.community.storage.FileStorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class CommunityPostImageServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T03:04:05Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final CommunityPostImageRepository repository = Mockito.mock(CommunityPostImageRepository.class);
	private final FileStorageService fileStorageService = Mockito.mock(FileStorageService.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final CommunityPostImageService service = new CommunityPostImageService(repository, fileStorageService,
		userQueryService, CLOCK);

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
	void loadImageFileReturnsResourceAndContentTypeWhenImageExists() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		when(repository.findById(7L)).thenReturn(Optional.of(image));
		Resource resource = Mockito.mock(Resource.class);
		when(fileStorageService.load("stored.png")).thenReturn(resource);

		CommunityPostImageFile file = service.loadImageFile(7L);

		assertThat(file.resource()).isSameAs(resource);
		assertThat(file.contentType()).isEqualTo("image/png");
	}

	@Test
	void loadImageFileFailsWithNotFoundWhenImageDoesNotExist() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.loadImageFile(404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(fileStorageService);
	}

	@Test
	void deleteImageIfPresentDeletesRowThenPhysicalFileWhenPostHasImage() {
		User uploader = User.create("uploader@finplay.com", "hash", "uploader", LocalDateTime.now(CLOCK));
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 10L, LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(
			uploader, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "image", image);

		service.deleteImageIfPresent(post);

		InOrder inOrder = Mockito.inOrder(repository, fileStorageService);
		inOrder.verify(repository).delete(image);
		inOrder.verify(fileStorageService).delete("stored.png");
	}

	@Test
	void deleteImageIfPresentDoesNothingWhenPostHasNoImage() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));

		service.deleteImageIfPresent(post);

		verify(repository, never()).delete(any());
		verifyNoInteractions(fileStorageService);
	}
}
