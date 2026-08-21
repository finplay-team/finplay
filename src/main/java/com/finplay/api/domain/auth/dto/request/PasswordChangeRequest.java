// 비밀번호 변경 요청의 현재 비밀번호와 새 비밀번호를 담는 요청 DTO
package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// currentPassword에는 최소 길이를 걸지 않는다 — 값이 틀린 것은 400이 아니라 403이며(대조는 서비스 책임),
// 최소 길이를 걸면 "정책상 존재할 수 없는 비밀번호"라는 정보가 응답 코드로 새어 나간다.
public record PasswordChangeRequest(
	@NotBlank(message = "현재 비밀번호는 필수입니다.") @Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String currentPassword,
	@NotBlank(message = "새 비밀번호는 필수입니다.") @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
	String newPassword) {
}
