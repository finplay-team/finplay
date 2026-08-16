// OAuth 재인증 콜백이 발급한 1회용 교환 코드로 실제 reauthToken을 요청하는 DTO
package com.finplay.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReauthExchangeRequest(
	@NotBlank(message = "교환 코드는 필수입니다.") @Size(max = 100, message = "교환 코드는 최대 100자까지 입력할 수 있습니다.")
	String code) {
}
