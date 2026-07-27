// 인증 사용자 기반 게시글 생성 규칙과 저장 실패 경계를 검증하는 단위 테스트다.
package com.finplay.api.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.dto.response.CommunityPostListResponse;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.repository.CommunityPostRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class CommunityPostServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T03:04:05Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final CommunityPostRepository repository = Mockito.mock(CommunityPostRepository.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final CommunityPostService service = new CommunityPostService(repository, userQueryService, CLOCK);

	@Test
	void createPostSavesAuthenticatedUserAndFixedCreationTime() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CommunityPostResponse response = service.createPost(42L, "title", "content");

		ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
		verify(repository).save(postCaptor.capture());
		assertThat(postCaptor.getValue().getAuthor()).isSameAs(author);
		assertThat(response.authorNickname()).isEqualTo("author");
		assertThat(response.title()).isEqualTo("title");
		assertThat(response.content()).isEqualTo("content");
		assertThat(response.createdAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response.updatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
	}

	@Test
	void createPostDoesNotSaveWhenAuthenticatedUserDoesNotExist() {
		when(userQueryService.getUser(404L))
			.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		assertThatThrownBy(() -> service.createPost(404L, "title", "content"))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);
		verify(repository, never()).save(any());
	}

	@Test
	void getPostsMapsRepositoryPageToListResponseWithPageMetadata() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", LocalDateTime.now(CLOCK));
		Page<CommunityPost> page = new PageImpl<>(List.of(post), PageRequest.of(0, 10), 1);
		when(repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10))).thenReturn(page);

		CommunityPostListResponse response = service.getPosts(0, 10);

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).authorNickname()).isEqualTo("author");
		assertThat(response.content().get(0).title()).isEqualTo("title");
		assertThat(response.page()).isEqualTo(0);
		assertThat(response.size()).isEqualTo(10);
		assertThat(response.totalElements()).isEqualTo(1);
		assertThat(response.totalPages()).isEqualTo(1);
		assertThat(response.hasNext()).isFalse();
	}

	@Test
	void getPostsReturnsEmptyContentWhenNoPostsExist() {
		Page<CommunityPost> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
		when(repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10))).thenReturn(emptyPage);

		CommunityPostListResponse response = service.getPosts(0, 10);

		assertThat(response.content()).isEmpty();
		assertThat(response.totalElements()).isEqualTo(0);
		assertThat(response.totalPages()).isEqualTo(0);
	}
}
