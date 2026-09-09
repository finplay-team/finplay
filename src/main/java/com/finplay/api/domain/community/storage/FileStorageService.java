package com.finplay.api.domain.community.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

	String store(MultipartFile file, String storedFilename);

	Resource load(String storedFilename);

	void delete(String storedFilename);
}
