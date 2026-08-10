// 커뮤니티 게시물에 첨부되는 이미지(업로더·연결 게시물·저장 정보)를 표현하는 엔티티
package com.finplay.api.community.domain;

import com.finplay.api.auth.domain.User;
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
@Table(name = "community_post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "uploader_id", nullable = false)
	private User uploader;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id")
	private CommunityPost post;

	@Column(name = "stored_filename", nullable = false, length = 255)
	private String storedFilename;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "content_type", nullable = false, length = 100)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private CommunityPostImage(
		User uploader,
		String storedFilename,
		String originalFilename,
		String contentType,
		long sizeBytes,
		LocalDateTime createdAt) {
		this.uploader = uploader;
		this.storedFilename = storedFilename;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.createdAt = createdAt;
	}

	public static CommunityPostImage create(
		User uploader,
		String storedFilename,
		String originalFilename,
		String contentType,
		long sizeBytes,
		LocalDateTime now) {
		return new CommunityPostImage(uploader, storedFilename, originalFilename, contentType, sizeBytes, now);
	}

	public boolean isAssigned() {
		return post != null;
	}

	public void assignToPost(CommunityPost post) {
		this.post = post;
	}
}
