package com.finplay.api.domain.community.service;

import com.finplay.api.domain.community.dto.response.CommunityPostLikeResponse;

public record CommunityPostLikeOutcome(
	CommunityPostLikeResponse response,
	boolean created) {
}
