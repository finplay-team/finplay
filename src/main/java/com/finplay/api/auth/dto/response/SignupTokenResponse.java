// 이메일 인증 완료 후 회원가입에 사용할 일회성 토큰을 반환하는 응답 DTO.
package com.finplay.api.auth.dto.response;

public record SignupTokenResponse(String signupVerificationToken, long expiresInSeconds) {
}
