// 이메일 로그인 자격증명 입력을 검증하는 요청 DTO
package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.")
	String email,
	// 로그인은 비밀번호 정책 검사가 아니라 자격증명 대조이므로 최소 길이를 걸지 않는다.
	// min을 두면 짧은 입력만 400, 나머지는 401로 갈려 응답이 저장된 자격증명의 힌트가 된다.
	@NotBlank(message = "비밀번호는 필수입니다.") @Size(max = 100, message = "비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String password) {
}
