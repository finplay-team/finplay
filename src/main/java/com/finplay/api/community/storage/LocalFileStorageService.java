// 로컬 파일시스템에 커뮤니티 게시물 첨부 이미지를 저장·조회·삭제하는 구현체
package com.finplay.api.community.storage;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

// prod는 S3FileStorageService를 쓴다 — 로컬 파일시스템은 인스턴스 간 공유되지 않는다
// (ai/specs/022-community-enhancement/plan.md "COM-006 후속: 이미지 저장소를 S3로 전환").
@Profile("!prod")
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

	private final Path baseDirectory;

	// @Value 주입 필드가 있는 빈은 Lombok @RequiredArgsConstructor를 쓰지 않고 생성자를 손으로 작성한다
	// (ai/agent-mistakes.md 2026-07-30 — Lombok은 @Value를 생성자 파라미터로 복사하지 않는다).
	public LocalFileStorageService(
		@Value("${finplay.community.image-storage.base-directory}")
		String baseDirectory) {
		this.baseDirectory = Path.of(baseDirectory);
	}

	@Override
	public String store(MultipartFile file, String storedFilename) {
		try {
			Files.createDirectories(baseDirectory);
			Path target = resolveWithinBaseDirectory(storedFilename);
			Files.copy(file.getInputStream(), target);
			return storedFilename;
		} catch (IOException e) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.");
		}
	}

	@Override
	public Resource load(String storedFilename) {
		Path target = resolveWithinBaseDirectory(storedFilename);
		if (!Files.exists(target)) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
		try {
			return new UrlResource(target.toUri());
		} catch (IOException e) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
	}

	@Override
	public void delete(String storedFilename) {
		Path target = resolveWithinBaseDirectory(storedFilename);
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.warn("커뮤니티 이미지 파일 삭제 실패 storedFilename={}", storedFilename, e);
		}
	}

	// storedFilename 생성 규칙이 서버 결정 확장자로 바뀌어도, 저장소 스스로 경계를 검사해 상위 디렉터리
	// 탈출을 막는다(PR #269 리뷰 — "지금 안전한 이유가 설계가 아니라 우연"이라는 지적을 저장소 계층에서
	// 방어로 고정한다).
	private Path resolveWithinBaseDirectory(String storedFilename) {
		Path base = baseDirectory.toAbsolutePath().normalize();
		Path target = base.resolve(storedFilename).normalize();
		if (!target.startsWith(base)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "잘못된 파일 경로입니다.");
		}
		return target;
	}
}
