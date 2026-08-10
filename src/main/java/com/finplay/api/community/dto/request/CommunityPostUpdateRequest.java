// 커뮤니티 게시글 수정 요청 — instrumentId 키의 부재(태그 보존)와 명시적 null(태그 해제)을 구분한다
package com.finplay.api.community.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CommunityPostUpdateRequestDeserializer.class)
public record CommunityPostUpdateRequest(
	@NotBlank(message = "제목은 필수입니다.") @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
	String title,
	@NotBlank(message = "본문은 필수입니다.") @Size(max = 5000, message = "본문은 최대 5000자까지 입력할 수 있습니다.")
	String content,
	// docs/conventions.md:51의 Wrapper 타입 규칙 예외 — 클라이언트가 보내는 값이 아니라 CommunityPostUpdateRequestDeserializer가
	// "instrumentId" 키 존재 여부로 항상 채우는 파생값이라 primitive여도 null 검증이 우회될 위험이 없다.
	@Schema(hidden = true)
	boolean instrumentIdProvided,
	Long instrumentId) {
}
