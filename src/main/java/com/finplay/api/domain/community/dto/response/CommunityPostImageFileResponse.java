package com.finplay.api.domain.community.dto.response;

import org.springframework.core.io.Resource;

public record CommunityPostImageFileResponse(
	Resource resource,
	String contentType) {
}
