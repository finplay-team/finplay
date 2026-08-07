// LocalFileStorageService의 저장·조회·삭제 왕복을 검증하는 단위 테스트
package com.finplay.api.community.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

class LocalFileStorageServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void storesAndLoadsFileRoundTrip() throws IOException {
		LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());
		byte[] content = "이미지 바이트".getBytes(StandardCharsets.UTF_8);
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));

		String storedFilename = service.store(file, "test-image.png");

		assertThat(storedFilename).isEqualTo("test-image.png");
		Resource loaded = service.load("test-image.png");
		assertThat(loaded.exists()).isTrue();
		assertThat(loaded.getContentAsByteArray()).isEqualTo(content);
	}

	@Test
	void deleteRemovesStoredFile() throws IOException {
		LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream())
			.thenReturn(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
		service.store(file, "to-delete.png");

		service.delete("to-delete.png");

		assertThatThrownBy(() -> service.load("to-delete.png"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
	}

	@Test
	void loadThrowsNotFoundWhenFileWasNeverStored() {
		LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());

		assertThatThrownBy(() -> service.load("never-stored.png"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
	}

	@Test
	void deleteDoesNotThrowWhenFileDoesNotExist() {
		LocalFileStorageService service = new LocalFileStorageService(tempDir.toString());

		assertThatCode(() -> service.delete("never-existed.png")).doesNotThrowAnyException();
	}
}
