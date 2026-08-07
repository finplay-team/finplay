// 커뮤니티 게시글 댓글 생성 요청의 본문과 입력 제한을 정의하는 DTO
package com.finplay.api.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCommentCreateRequest(
	@NotBlank(message = "댓글 내용은 필수입니다.") @Size(max = 1000, message = "댓글 내용은 최대 1,000자까지 입력할 수 있습니다.")
	String content,
	Long parentCommentId) {
}
