package com.finplay.api.domain.community.storage;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

	private final S3Client s3Client;
	private final CommunityS3StorageProperties properties;

	@Override
	public String store(MultipartFile file, String storedFilename) {
		try {
			PutObjectRequest request = PutObjectRequest.builder()
				.bucket(properties.bucket())
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
			GetObjectRequest request = GetObjectRequest.builder().bucket(properties.bucket()).key(storedFilename)
				.build();
			ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
			return new S3ObjectResource(response, response.response().contentLength());
		} catch (NoSuchKeyException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		} catch (S3Exception e) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이미지 조회에 실패했습니다.");
		}
	}

	@Override
	public void delete(String storedFilename) {
		try {
			DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(properties.bucket()).key(storedFilename)
				.build();
			s3Client.deleteObject(request);
		} catch (S3Exception e) {
			log.warn("커뮤니티 이미지 파일 삭제 실패 storedFilename={}", storedFilename, e);
		}
	}

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

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj);
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
	}
}
