// 커뮤니티 게시글 수정 요청 — instrumentId 키의 부재(태그 보존)와 명시적 null(태그 해제)을 구분한다
package com.finplay.api.community.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CommunityPostUpdateRequestDeserializer.class)
public record CommunityPostUpdateRequest(
	@NotBlank(message = "제목은 필수입니다.") @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
	String title,
	@NotBlank(message = "본문은 필수입니다.") @Size(max = 5000, message = "본문은 최대 5000자까지 입력할 수 있습니다.")
	String content,
	boolean instrumentIdProvided,
	Long instrumentId) {
}
