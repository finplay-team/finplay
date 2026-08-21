// 생성된 커뮤니티 게시글과 작성자 닉네임을 노출하는 응답 DTO
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

	// 좋아요 여부·매매 카드 요약 둘 다 요청자 컨텍스트·서비스 호출이 있어야 결정되므로 인자 없는 from(post)
	// 오버로드는 두지 않는다 — 호출부가 실제 값을 넘기지 않고 하드코딩하는 실수를 막기 위함(spec 045 plan.md,
	// spec 046 TRADESHARE-003). sharedTrade는 엔티티 필드가 아니라 PostSellFeedbackService 호출 결과라 이 record가
	// 직접 계산하지 않고 호출부(CommunityPostService)가 조립해 넘긴다.
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
