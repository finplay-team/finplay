// 인증 사용자의 식별자·이메일·닉네임·가입 방식만 반환하는 내 정보 조회 응답 DTO
package com.finplay.api.domain.auth.dto.response;

import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.User;

public record MemberResponse(Long id, String email, String nickname, SignupMethod signupMethod) {

	public static MemberResponse from(User user, SignupMethod signupMethod) {
		return new MemberResponse(user.getId(), user.getEmail(), user.getNickname(), signupMethod);
	}
}
