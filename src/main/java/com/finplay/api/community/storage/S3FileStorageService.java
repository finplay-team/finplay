// S3에 커뮤니티 게시물 첨부 이미지를 저장·조회·삭제하는 구현체 (prod 전용)
package com.finplay.api.community.storage;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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

// 버킷명은 CommunityS3StorageProperties(@ConfigurationProperties)로 받는다 — @Value 필드 대신 프로퍼티
// record를 주입받으면 파생 로직 없는 파라미터 직접 대입만으로 Lombok @RequiredArgsConstructor를 쓸 수
// 있다(SpotBugs EI_EXPOSE_REP2 회피, docs/agent-mistakes.md 2026-07-29 항목 — 손으로 쓴 생성자의 가변
// 필드(S3Client) 저장만 EI_EXPOSE_REP2로 잡히고 Lombok이 생성한 생성자는 잡히지 않는다).
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

		// contentLength는 다운로드 응답 헤더 계산용 파생 필드일 뿐 동등성 기준이 아니다 — 상위 클래스
		// (InputStreamResource, 내부 InputStream 식별자 기반)의 동등성 규칙을 그대로 유지함을 명시한다
		// (SpotBugs EQ_DOESNT_OVERRIDE_EQUALS).
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
