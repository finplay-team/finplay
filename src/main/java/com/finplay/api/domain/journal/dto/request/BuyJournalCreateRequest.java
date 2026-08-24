// 매수 투자일기 작성에 필요한 본문을 검증하는 요청 DTO
package com.finplay.api.domain.journal.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BuyJournalCreateRequest(
	@NotBlank(message = "본문은 필수입니다.") @Size(max = 5000, message = "본문은 최대 5000자까지 입력할 수 있습니다.")
	String content) {
}
