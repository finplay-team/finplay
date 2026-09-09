package com.finplay.api.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.community.dto.response.CommunityPostLikeResponse;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostLike;
import com.finplay.api.domain.community.repository.CommunityPostLikeRepository;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
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
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
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
		when(communityPostRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.likePost(404L, 42L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(communityPostLikeRepository, userQueryService);
		verify(communityPostRepository, never()).incrementLikeCount(any());
	}

	@Test
	void likePostSucceedsWhenAuthenticatedUserIsThePostAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
		when(communityPostLikeRepository.existsByPost_IdAndUser_Id(7L, 42L)).thenReturn(false);
		when(userQueryService.getUser(42L)).thenReturn(author);

		assertThatCode(() -> service.likePost(7L, 42L)).doesNotThrowAnyException();

		verify(communityPostLikeRepository).saveAndFlush(any(CommunityPostLike.class));
		verify(communityPostRepository).incrementLikeCount(7L);
	}

	@Test
	void likePostAcquiresPostRowLockBeforeReadingLikeExistence() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
		when(communityPostLikeRepository.existsByPost_IdAndUser_Id(7L, 42L)).thenReturn(true);

		service.likePost(7L, 42L);

		InOrder inOrder = Mockito.inOrder(communityPostRepository, communityPostLikeRepository);
		inOrder.verify(communityPostRepository).findByIdForUpdate(7L);
		inOrder.verify(communityPostLikeRepository).existsByPost_IdAndUser_Id(7L, 42L);
	}

	@Test
	void unlikePostDeletesLikeAndDecrementsCountWhenLikeExists() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		User liker = User.create("liker@finplay.com", "hash", "liker", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		CommunityPostLike like = CommunityPostLike.create(post, liker, LocalDateTime.now(CLOCK));
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
		when(communityPostLikeRepository.findByPost_IdAndUser_Id(7L, 42L)).thenReturn(Optional.of(like));

		service.unlikePost(7L, 42L);

		verify(communityPostLikeRepository).delete(like);
		verify(communityPostRepository).decrementLikeCount(7L);
	}

	@Test
	void unlikePostDoesNothingWhenLikeDoesNotExist() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		when(communityPostRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(post));
		when(communityPostLikeRepository.findByPost_IdAndUser_Id(7L, 42L)).thenReturn(Optional.empty());

		assertThatCode(() -> service.unlikePost(7L, 42L)).doesNotThrowAnyException();

		verify(communityPostLikeRepository, never()).delete(any());
		verify(communityPostRepository, never()).decrementLikeCount(any());
	}

	@Test
	void unlikePostThrowsNotFoundAndDoesNotQueryLikesWhenPostDoesNotExist() {
		when(communityPostRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.unlikePost(404L, 42L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(communityPostLikeRepository);
		verify(communityPostRepository, never()).decrementLikeCount(any());
	}
}
