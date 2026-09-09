package com.finplay.api.domain.favorite.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FavoriteCreateRequest(
	@NotNull(message = "종목 ID는 필수입니다.") @Positive(message = "종목 ID는 양수여야 합니다.")
	Long instrumentId) {
}
