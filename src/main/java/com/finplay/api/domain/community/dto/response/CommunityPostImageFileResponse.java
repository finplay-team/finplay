// 커뮤니티 게시물 첨부 이미지 다운로드 응답에 필요한 바이트 리소스와 형식을 담는 DTO
package com.finplay.api.domain.community.dto.response;

import org.springframework.core.io.Resource;

public record CommunityPostImageFileResponse(
	Resource resource,
	String contentType) {
}
