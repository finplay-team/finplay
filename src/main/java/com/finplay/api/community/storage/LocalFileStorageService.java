// 로컬 파일시스템에 커뮤니티 게시물 첨부 이미지를 저장·조회·삭제하는 구현체
package com.finplay.api.community.storage;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

	private final Path baseDirectory;

	// @Value 주입 필드가 있는 빈은 Lombok @RequiredArgsConstructor를 쓰지 않고 생성자를 손으로 작성한다
	// (docs/agent-mistakes.md 2026-07-30 — Lombok은 @Value를 생성자 파라미터로 복사하지 않는다).
	public LocalFileStorageService(
		@Value("${finplay.community.image-storage.base-directory}")
		String baseDirectory) {
		this.baseDirectory = Path.of(baseDirectory);
	}

	@Override
	public String store(MultipartFile file, String storedFilename) {
		try {
			Files.createDirectories(baseDirectory);
			Path target = baseDirectory.resolve(storedFilename);
			Files.copy(file.getInputStream(), target);
			return storedFilename;
		} catch (IOException e) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.");
		}
	}

	@Override
	public Resource load(String storedFilename) {
		Path target = baseDirectory.resolve(storedFilename);
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
		Path target = baseDirectory.resolve(storedFilename);
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.warn("커뮤니티 이미지 파일 삭제 실패 storedFilename={}", storedFilename, e);
		}
	}
}
