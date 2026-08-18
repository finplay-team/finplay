// 게시물 좋아요 표시·취소 서비스의 멱등 처리, 본인 게시물 허용, 예외 전파를 검증하는 단위 테스트다.
package com.finplay.api.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostLike;
import com.finplay.api.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.community.repository.CommunityPostLikeRepository;
import com.finplay.api.community.repository.CommunityPostRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class CommunityPostLikeServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T03:04:05.123456Z"), ZoneOffset.UTC);

	private final CommunityPostRepository communityPostRepository = Mockito.mock(CommunityPostRepository.class);
	private final CommunityPostLikeRepository communityPostLikeRepository = Mockito
		.mock(CommunityPostLikeRepository.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final CommunityPostLikeService service = new CommunityPostLikeService(
		communityPostRepository, communityPostLikeRepository, userQueryService, CLOCK);

	@Test
	void likePostSavesNewLikeAndIncrementsCountWhenNotAlreadyLiked() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		User liker = User.create("liker@finplay.com", "hash", "liker", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(liker, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		when(communityPostRepository.existsById(7L)).thenReturn(true);
		when(communityPostRepository.getReferenceById(7L)).thenReturn(post);
		when(communityPostLikeRepository.existsByPost_IdAndUser_Id(7L, 42L)).thenReturn(false);
		when(userQueryService.getUser(42L)).thenReturn(liker);

		CommunityPostLikeOutcome outcome = service.likePost(7L, 42L);

		ArgumentCaptor<CommunityPostLike> captor = ArgumentCaptor.forClass(CommunityPostLike.class);
		verify(communityPostLikeRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getPost()).isSameAs(post);
		assertThat(captor.getValue().getUser()).isSameAs(liker);
		verify(communityPostRepository).incrementLikeCount(7L);
		assertThat(outcome.created()).isTrue();
		assertThat(outcome.response()).isEqualTo(new CommunityPostLikeResponse(7L, 1L, true));
	}

	@Test
	void likePostReturnsCurrentStateWithoutSavingOrIncrementingWhenAlreadyLiked() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "likeCount", 5L);
		when(communityPostRepository.existsById(7L)).thenReturn(true);
		when(communityPostRepository.getReferenceById(7L)).thenReturn(post);
		when(communityPostLikeRepository.existsByPost_IdAndUser_Id(7L, 42L)).thenReturn(true);

		CommunityPostLikeOutcome outcome = service.likePost(7L, 42L);

		assertThat(outcome.created()).isFalse();
		assertThat(outcome.response()).isEqualTo(new CommunityPostLikeResponse(7L, 5L, true));
		verify(communityPostLikeRepository, never()).saveAndFlush(any());
		verify(communityPostRepository, never()).incrementLikeCount(any());
		verifyNoInteractions(userQueryService);
	}

	@Test
	void likePostThrowsNotFoundAndDoesNotSaveOrIncrementWhenPostDoesNotExist() {
		when(communityPostRepository.existsById(404L)).thenReturn(false);

		assertThatThrownBy(() -> service.likePost(404L, 42L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(communityPostLikeRepository, userQueryService);
		verify(communityPostRepository, never()).incrementLikeCount(any());
	}

	// 본인 게시물에도 좋아요를 허용한다(spec 045 비즈니스 규칙) — 별도 차단 로직이 없음을 확인하는 회귀.
	@Test
	void likePostSucceedsWhenAuthenticatedUserIsThePostAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		when(communityPostRepository.existsById(7L)).thenReturn(true);
		when(communityPostRepository.getReferenceById(7L)).thenReturn(post);
		when(communityPostLikeRepository.existsByPost_IdAndUser_Id(7L, 42L)).thenReturn(false);
		when(userQueryService.getUser(42L)).thenReturn(author);

		assertThatCode(() -> service.likePost(7L, 42L)).doesNotThrowAnyException();

		verify(communityPostLikeRepository).saveAndFlush(any(CommunityPostLike.class));
		verify(communityPostRepository).incrementLikeCount(7L);
	}

	@Test
	void unlikePostDeletesLikeAndDecrementsCountWhenLikeExists() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		User liker = User.create("liker@finplay.com", "hash", "liker", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		CommunityPostLike like = CommunityPostLike.create(post, liker, LocalDateTime.now(CLOCK));
		when(communityPostRepository.existsById(7L)).thenReturn(true);
		when(communityPostLikeRepository.findByPost_IdAndUser_Id(7L, 42L)).thenReturn(Optional.of(like));

		service.unlikePost(7L, 42L);

		verify(communityPostLikeRepository).delete(like);
		verify(communityPostRepository).decrementLikeCount(7L);
	}

	@Test
	void unlikePostDoesNothingWhenLikeDoesNotExist() {
		when(communityPostRepository.existsById(7L)).thenReturn(true);
		when(communityPostLikeRepository.findByPost_IdAndUser_Id(7L, 42L)).thenReturn(Optional.empty());

		assertThatCode(() -> service.unlikePost(7L, 42L)).doesNotThrowAnyException();

		verify(communityPostLikeRepository, never()).delete(any());
		verify(communityPostRepository, never()).decrementLikeCount(any());
	}

	@Test
	void unlikePostThrowsNotFoundAndDoesNotQueryLikesWhenPostDoesNotExist() {
		when(communityPostRepository.existsById(404L)).thenReturn(false);

		assertThatThrownBy(() -> service.unlikePost(404L, 42L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(communityPostLikeRepository);
		verify(communityPostRepository, never()).decrementLikeCount(any());
	}
}
