// 좋아요 표시 처리 결과와 신규 생성 여부를 함께 전달해 컨트롤러가 상태코드(201/200)를 정하게 하는 내부 전달용 값
package com.finplay.api.domain.community.service;

import com.finplay.api.domain.community.dto.response.CommunityPostLikeResponse;

public record CommunityPostLikeOutcome(
	CommunityPostLikeResponse response,
	boolean created) {
}
