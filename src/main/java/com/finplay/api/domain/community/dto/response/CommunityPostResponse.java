package com.finplay.api.domain.community.dto.response;

import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostImage;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.market.entity.Instrument;
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
	String imageUrl,
	long likeCount,
	boolean likedByMe,
	TradeShareSummaryResponse sharedTrade) {

	public static CommunityPostResponse of(
		CommunityPost post, boolean likedByMe, TradeShareSummaryResponse sharedTrade) {
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
			image == null ? null : CommunityPostImageResponse.toImageUrl(image.getId()),
			post.getLikeCount(),
			likedByMe,
			sharedTrade);
	}
}
