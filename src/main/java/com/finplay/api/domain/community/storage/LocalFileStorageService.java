package com.finplay.api.domain.community.storage;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

@Profile("!prod")
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

	private final Path baseDirectory;

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

	private Path resolveWithinBaseDirectory(String storedFilename) {
		Path base = baseDirectory.toAbsolutePath().normalize();
		Path target = base.resolve(storedFilename).normalize();
		if (!target.startsWith(base)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "잘못된 파일 경로입니다.");
		}
		return target;
	}
}
