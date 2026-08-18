// 생성된 커뮤니티 게시글과 작성자 닉네임을 노출하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.feedback.dto.response.TradeShareSummaryResponse;
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
	TradeShareSummaryResponse sharedTrade) {

	// sharedTradeId가 없는 게시물(대부분)이 이 정적 팩토리를 쓴다 — sharedTrade는 항상 null이다.
	public static CommunityPostResponse from(CommunityPost post) {
		return of(post, null);
	}

	// sharedTrade는 엔티티 필드가 아니라 PostSellFeedbackService 호출 결과다(TRADESHARE-003) — 그래서 이 record가
	// 직접 계산하지 않고 호출부(CommunityPostService)가 조립해 넘긴다.
	public static CommunityPostResponse of(CommunityPost post, TradeShareSummaryResponse sharedTrade) {
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
			sharedTrade);
	}
}
