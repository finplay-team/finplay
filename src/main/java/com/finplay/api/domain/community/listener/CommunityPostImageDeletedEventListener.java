package com.finplay.api.domain.community.listener;

import com.finplay.api.domain.community.event.CommunityPostImageDeletedEvent;
import com.finplay.api.domain.community.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class CommunityPostImageDeletedEventListener {

	private final FileStorageService fileStorageService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCommunityPostImageDeleted(CommunityPostImageDeletedEvent event) {
		try {
			fileStorageService.delete(event.storedFilename());
		} catch (Exception e) {
			log.error("커뮤니티 이미지 파일 삭제 처리 중 예외 발생. storedFilename={}", event.storedFilename(), e);
		}
	}
}
