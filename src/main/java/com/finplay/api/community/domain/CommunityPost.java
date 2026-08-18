// 커뮤니티 게시글의 작성자와 본문 및 생성·수정 시각을 표현하는 엔티티
package com.finplay.api.community.domain;

import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "community_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "instrument_id")
	private Instrument instrument;

	// order 도메인 Trade 엔티티를 직접 참조하지 않고 id만 저장한다(ADR-0002) — 조회는 항상
	// PostSellFeedbackService.getTradeShareSummary를 거친다.
	@Column(name = "shared_trade_id")
	private Long sharedTradeId;

	@OneToOne(mappedBy = "post", fetch = FetchType.LAZY)
	private CommunityPostImage image;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 5000)
	private String content;

	@Column(name = "like_count", nullable = false)
	private long likeCount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private CommunityPost(
		User author,
		String title,
		String content,
		Instrument instrument,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {
		this.author = author;
		this.title = title;
		this.content = content;
		this.instrument = instrument;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static CommunityPost create(
		User author, String title, String content, Instrument instrument, LocalDateTime now) {
		return new CommunityPost(author, title, content, instrument, now, now);
	}

	public void update(String title, String content, Instrument instrument, LocalDateTime now) {
		this.title = title;
		this.content = content;
		this.instrument = instrument;
		this.updatedAt = now;
	}

	// 소유 측(CommunityPostImage.assignToPost)과 함께 호출해 역방향 필드도 즉시 동기화한다 —
	// Hibernate는 mappedBy 역방향 필드를 같은 영속성 컨텍스트 내에서 자동으로 채워주지 않는다.
	public void attachImage(CommunityPostImage image) {
		this.image = image;
	}

	// 생성 시 한 번만 붙이고 이후 수정하지 않는다 — update()가 이 필드를 받지 않는 것과 같은 이유다(spec 범위 제외).
	public void attachSharedTrade(Long sharedTradeId) {
		this.sharedTradeId = sharedTradeId;
	}
}
