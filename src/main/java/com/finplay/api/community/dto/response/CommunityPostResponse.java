// 생성된 커뮤니티 게시글과 작성자 닉네임을 노출하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.market.domain.Instrument;
import java.time.LocalDateTime;

public record CommunityPostResponse(
	Long postId,
	String authorNickname,
	String title,
	String content,
	LocalDateTime createdAt,
	LocalDateTime updatedAt,
	Long instrumentId,
	String instrumentSymbol,
	String instrumentName,
	Long imageId,
	String imageUrl) {

	public static CommunityPostResponse from(CommunityPost post) {
		Instrument instrument = post.getInstrument();
		CommunityPostImage image = post.getImage();
		return new CommunityPostResponse(
			post.getId(),
			post.getAuthor().getNickname(),
			post.getTitle(),
			post.getContent(),
			post.getCreatedAt(),
			post.getUpdatedAt(),
			instrument == null ? null : instrument.getId(),
			instrument == null ? null : instrument.getSymbol(),
			instrument == null ? null : instrument.getName(),
			image == null ? null : image.getId(),
			image == null ? null : CommunityPostImageResponse.toImageUrl(image.getId()));
	}
}
