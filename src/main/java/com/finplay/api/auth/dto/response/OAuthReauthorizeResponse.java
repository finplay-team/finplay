// 재인증용 OAuth 인가 URI를 담아 브라우저 이동을 클라이언트에 맡기는 응답 DTO
package com.finplay.api.auth.dto.response;

public record OAuthReauthorizeResponse(String authorizationUri) {
}
