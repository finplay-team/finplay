package com.finplay.api.domain.community.dto.request;

import com.finplay.api.domain.community.deserializer.CommunityPostUpdateRequestDeserializer;
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
	@Schema(hidden = true)
	boolean instrumentIdProvided,
	Long instrumentId) {
}
