// S3에 커뮤니티 게시물 첨부 이미지를 저장·조회·삭제하는 구현체 (prod 전용)
package com.finplay.api.community.storage;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Profile("prod")
@Slf4j
@Service
public class S3FileStorageService implements FileStorageService {

	private final S3Client s3Client;
	private final String bucket;

	// @Value 주입 필드가 있는 빈은 Lombok @RequiredArgsConstructor를 쓰지 않고 생성자를 손으로 작성한다
	// (docs/agent-mistakes.md 2026-07-30 — Lombok은 @Value를 생성자 파라미터로 복사하지 않는다).
	public S3FileStorageService(
		S3Client s3Client,
		@Value("${finplay.community.image-storage.s3.bucket}")
		String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	@Override
	public String store(MultipartFile file, String storedFilename) {
		try {
			PutObjectRequest request = PutObjectRequest.builder()
				.bucket(bucket)
				.key(storedFilename)
				.contentType(file.getContentType())
				.build();
			s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
			return storedFilename;
		} catch (S3Exception | IOException e) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.");
		}
	}

	@Override
	public Resource load(String storedFilename) {
		try {
			GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(storedFilename).build();
			ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
			return new S3ObjectResource(response, response.response().contentLength());
		} catch (NoSuchKeyException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
	}

	@Override
	public void delete(String storedFilename) {
		try {
			DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(storedFilename).build();
			s3Client.deleteObject(request);
		} catch (S3Exception e) {
			log.warn("커뮤니티 이미지 파일 삭제 실패 storedFilename={}", storedFilename, e);
		}
	}

	// InputStreamResource는 contentLength()가 정의되지 않아(-1) 다운로드 응답의 Content-Length 헤더가
	// 빠질 수 있다 — GetObjectResponse.contentLength()를 그대로 반환하도록 오버라이드한다.
	private static final class S3ObjectResource extends InputStreamResource {

		private final long contentLength;

		S3ObjectResource(InputStream inputStream, long contentLength) {
			super(inputStream);
			this.contentLength = contentLength;
		}

		@Override
		public long contentLength() {
			return contentLength;
		}
	}
}
