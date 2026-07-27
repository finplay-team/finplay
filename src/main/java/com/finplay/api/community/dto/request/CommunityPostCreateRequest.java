// 커뮤니티 게시글 생성에 필요한 제목과 본문을 검증하는 요청 DTO
package com.finplay.api.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommunityPostCreateRequest(
	@NotBlank(message = "제목은 필수입니다.") @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
	String title,
	@NotBlank(message = "본문은 필수입니다.") @Size(max = 5000, message = "본문은 최대 5000자까지 입력할 수 있습니다.")
	String content) {
}
