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
	String imageUrl,
	long likeCount,
	boolean likedByMe) {

	// 좋아요 여부는 요청자 컨텍스트가 있어야 결정되므로 인자 없는 from(post) 오버로드는 두지 않는다 —
	// 호출부가 실제 좋아요 상태를 넘기지 않고 false로 하드코딩하는 실수를 막기 위함(spec 045 plan.md).
	public static CommunityPostResponse from(CommunityPost post, boolean likedByMe) {
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
			likedByMe);
	}
}
