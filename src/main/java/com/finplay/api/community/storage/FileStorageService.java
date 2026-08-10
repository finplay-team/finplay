// 커뮤니티 게시물 첨부 이미지의 저장·조회·삭제를 추상화하는 인터페이스
package com.finplay.api.community.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

	// 파일을 저장하고 저장 경로/키(현재 구현은 storedFilename 그대로)를 반환한다.
	String store(MultipartFile file, String storedFilename);

	// 저장된 파일을 다운로드용 Resource로 반환한다. 존재하지 않으면 구현체가 예외를 던진다.
	Resource load(String storedFilename);

	// 저장된 파일을 삭제한다. best-effort — 실패해도 예외를 던지지 않는다.
	void delete(String storedFilename);
}
