// 게시물 첨부 이미지의 DB 행이 삭제됐음을 알리는 이벤트 (물리 파일 삭제는 커밋 이후로 미룬다)
package com.finplay.api.domain.community.event;

public record CommunityPostImageDeletedEvent(String storedFilename) {
}
