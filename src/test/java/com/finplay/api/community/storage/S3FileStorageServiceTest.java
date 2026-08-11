// S3FileStorageService의 저장·조회·삭제와 예외 변환 계약을 검증하는 단위 테스트 (S3Client mock)
package com.finplay.api.community.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3FileStorageServiceTest {

	private static final String BUCKET = "test-bucket";

	@Test
	void storeUploadsWithBucketKeyAndContentType() throws IOException {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		byte[] content = "이미지 바이트".getBytes(StandardCharsets.UTF_8);
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
		when(file.getContentType()).thenReturn("image/png");
		when(file.getSize()).thenReturn((long)content.length);

		String storedFilename = service.store(file, "test-image.png");

		assertThat(storedFilename).isEqualTo("test-image.png");
		ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(s3Client).putObject(captor.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
		PutObjectRequest request = captor.getValue();
		assertThat(request.bucket()).isEqualTo(BUCKET);
		assertThat(request.key()).isEqualTo("test-image.png");
		assertThat(request.contentType()).isEqualTo("image/png");
	}

	@Test
	void storeThrowsInternalErrorWhenS3PutFails() throws IOException {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream())
			.thenReturn(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
		when(file.getContentType()).thenReturn("image/png");
		when(s3Client.putObject(
			any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
			.thenThrow(S3Exception.builder().message("boom").build());

		assertThatThrownBy(() -> service.store(file, "test-image.png"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_ERROR);
	}

	@Test
	void loadReturnsResourceWithBytesAndContentLengthFromS3() throws IOException {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		byte[] content = "이미지 바이트".getBytes(StandardCharsets.UTF_8);
		GetObjectResponse response = GetObjectResponse.builder().contentLength((long)content.length).build();
		ResponseInputStream<GetObjectResponse> responseInputStream = new ResponseInputStream<>(response,
			AbortableInputStream.create(new ByteArrayInputStream(content)));
		when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseInputStream);

		Resource loaded = service.load("test-image.png");

		assertThat(loaded.contentLength()).isEqualTo(content.length);
		assertThat(loaded.getInputStream().readAllBytes()).isEqualTo(content);
	}

	@Test
	void loadThrowsNotFoundWhenKeyDoesNotExist() {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		when(s3Client.getObject(any(GetObjectRequest.class)))
			.thenThrow(NoSuchKeyException.builder().message("not found").build());

		assertThatThrownBy(() -> service.load("missing.png"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
	}

	@Test
	void loadThrowsInternalErrorWhenS3Fails() {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		when(s3Client.getObject(any(GetObjectRequest.class)))
			.thenThrow(S3Exception.builder().message("boom").build());

		assertThatThrownBy(() -> service.load("test-image.png"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_ERROR);
	}

	@Test
	void deleteCallsDeleteObjectWithBucketAndKey() {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));

		service.delete("to-delete.png");

		ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
		verify(s3Client).deleteObject(captor.capture());
		assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
		assertThat(captor.getValue().key()).isEqualTo("to-delete.png");
	}

	@Test
	void deleteDoesNotThrowWhenS3Fails() {
		S3Client s3Client = mock(S3Client.class);
		S3FileStorageService service = new S3FileStorageService(s3Client, new CommunityS3StorageProperties(BUCKET));
		when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
			.thenThrow(S3Exception.builder().message("boom").build());

		assertThatCode(() -> service.delete("to-delete.png")).doesNotThrowAnyException();
	}
}
