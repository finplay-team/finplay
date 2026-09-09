package com.finplay.api.domain.journal.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BuyJournalUpdateRequest(
	@NotBlank(message = "본문은 필수입니다.") @Size(max = 5000, message = "본문은 최대 5000자까지 입력할 수 있습니다.")
	String content) {
}
