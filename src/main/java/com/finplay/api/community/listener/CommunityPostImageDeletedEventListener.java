// 게시물 첨부 이미지 삭제 이벤트를 커밋 이후(after-commit)에 구독해 물리 파일을 정리하는 리스너
package com.finplay.api.community.listener;

import com.finplay.api.community.event.CommunityPostImageDeletedEvent;
import com.finplay.api.community.storage.FileStorageService;
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

	// DB 트랜잭션이 롤백되면 물리 파일을 지우지 않는다 — 되돌릴 수 없는 삭제라 커밋이 확정된 뒤에만
	// 수행한다(RankingEventListener의 AFTER_COMMIT 선례, PR #269 리뷰). 삭제 자체는 best-effort이며
	// 실패해도 이 리스너 밖으로 예외를 전파하지 않는다.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCommunityPostImageDeleted(CommunityPostImageDeletedEvent event) {
		try {
			fileStorageService.delete(event.storedFilename());
		} catch (Exception e) {
			log.error("커뮤니티 이미지 파일 삭제 처리 중 예외 발생. storedFilename={}", event.storedFilename(), e);
		}
	}
}
