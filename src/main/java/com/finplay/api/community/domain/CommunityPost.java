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

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 5000)
	private String content;

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
}
