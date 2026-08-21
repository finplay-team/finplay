// 댓글 생성 서비스의 연관관계, 고정 시각, 실패 시 부수 효과를 검증하는 단위 테스트다.
package com.finplay.api.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.community.dto.response.PostCommentResponse;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.PostComment;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.community.repository.PostCommentRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class PostCommentServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-27T03:04:05.123456Z"), ZoneOffset.UTC);

	private final CommunityPostRepository postRepository = Mockito.mock(CommunityPostRepository.class);
	private final PostCommentRepository commentRepository = Mockito.mock(PostCommentRepository.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final PostCommentService service = new PostCommentService(postRepository, commentRepository,
		userQueryService, CLOCK);

	@Test
	void createCommentSavesPostAuthenticatedAuthorContentAndFixedTime() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> {
			PostComment comment = invocation.getArgument(0);
			ReflectionTestUtils.setField(comment, "id", 9L);
			return comment;
		});

		PostCommentResponse response = service.createComment(7L, 42L, "comment", null);

		ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getPost()).isSameAs(post);
		assertThat(captor.getValue().getAuthor()).isSameAs(author);
		assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response).isEqualTo(new PostCommentResponse(
			9L, "author", "comment", LocalDateTime.now(CLOCK), null, List.of()));
	}

	@Test
	void createCommentDoesNotLookupUserOrSaveWhenPostDoesNotExist() {
		when(postRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createComment(404L, 42L, "comment", null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(userQueryService);
		verify(commentRepository, never()).save(any());
	}

	@Test
	void createCommentDoesNotSaveWhenAuthenticatedUserDoesNotExist() {
		User postAuthor = User.create("post@finplay.com", "hash", "poster", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(postAuthor, "title", "post", null, LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(404L)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		assertThatThrownBy(() -> service.createComment(7L, 404L, "comment", null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);

		verify(commentRepository, never()).save(any());
	}

	@Test
	void createCommentSavesWithNullParentCommentWhenParentCommentIdIsNull() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.createComment(7L, 42L, "comment", null);

		ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getParentComment()).isNull();
		verify(commentRepository, never()).findById(any());
	}

	@Test
	void createCommentSavesReplyWithParentCommentWhenParentBelongsToSamePost() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 7L);
		PostComment parent = PostComment.create(post, author, "parent comment", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parent, "id", 3L);
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(3L)).thenReturn(Optional.of(parent));
		when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> {
			PostComment comment = invocation.getArgument(0);
			ReflectionTestUtils.setField(comment, "id", 10L);
			return comment;
		});

		PostCommentResponse response = service.createComment(7L, 42L, "reply", 3L);

		ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getParentComment()).isSameAs(parent);
		assertThat(response.parentCommentId()).isEqualTo(3L);
	}

	@Test
	void createCommentThrowsNotFoundWhenParentCommentDoesNotExist() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createComment(7L, 42L, "reply", 999L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(commentRepository, never()).save(any());
	}

	@Test
	void createCommentThrowsNotFoundWhenParentCommentBelongsToDifferentPost() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		CommunityPost otherPost = CommunityPost.create(author, "other title", "other post", null,
			LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 7L);
		ReflectionTestUtils.setField(otherPost, "id", 8L);
		PostComment parentOnOtherPost = PostComment.create(
			otherPost, author, "parent on other post", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parentOnOtherPost, "id", 3L);
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(3L)).thenReturn(Optional.of(parentOnOtherPost));

		assertThatThrownBy(() -> service.createComment(7L, 42L, "reply", 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(commentRepository, never()).save(any());
	}

	@Test
	void createCommentThrowsValidationErrorWhenParentCommentIsAlreadyAReply() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 7L);
		PostComment grandparent = PostComment.create(post, author, "grandparent", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(grandparent, "id", 2L);
		PostComment parent = PostComment.create(
			post, author, "already a reply", grandparent, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parent, "id", 3L);
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(3L)).thenReturn(Optional.of(parent));

		assertThatThrownBy(() -> service.createComment(7L, 42L, "reply to reply", 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verify(commentRepository, never()).save(any());
	}

	// 이슈 #277 / PR #331 리뷰 참고 사항 #2: tombstone된 부모에는 새 대댓글을 남길 수 없다.
	@Test
	void createCommentThrowsValidationErrorWhenParentCommentIsTombstoned() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 7L);
		PostComment tombstonedParent = PostComment.create(
			post, author, "original content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(tombstonedParent, "id", 3L);
		tombstonedParent.tombstone(LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(3L)).thenReturn(Optional.of(tombstonedParent));

		assertThatThrownBy(() -> service.createComment(7L, 42L, "reply", 3L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		verify(commentRepository, never()).save(any());
	}

	// 대조 케이스: tombstone되지 않은 정상 부모에는 여전히 답글을 남길 수 있어야 한다.
	@Test
	void createCommentSavesReplyWhenParentCommentIsNotTombstoned() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 7L);
		PostComment liveParent = PostComment.create(post, author, "live content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(liveParent, "id", 3L);
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.findById(3L)).thenReturn(Optional.of(liveParent));
		when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> {
			PostComment comment = invocation.getArgument(0);
			ReflectionTestUtils.setField(comment, "id", 10L);
			return comment;
		});

		PostCommentResponse response = service.createComment(7L, 42L, "reply", 3L);

		assertThat(response.parentCommentId()).isEqualTo(3L);
		verify(commentRepository).save(any(PostComment.class));
	}

	// 이슈 #277: 최상위 댓글(parentComment == null)은 하드 삭제 대신 tombstone된다 — 자식을 가질 수 있는
	// 위치이므로 실제로 지우면 자식이 부모를 잃는다. delete()는 호출되지 않아야 한다.
	@Test
	void deleteCommentTombstonesTopLevelCommentInsteadOfHardDeletingWhenAuthenticatedUserIsAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment comment = PostComment.create(post, author, "comment", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		ReflectionTestUtils.setField(comment, "id", 9L);
		when(commentRepository.findById(9L)).thenReturn(Optional.of(comment));

		service.deleteComment(42L, 9L);

		assertThat(comment.isTombstoned()).isTrue();
		assertThat(comment.getDeletedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		verify(commentRepository, never()).delete(any());
	}

	// 대댓글(parentComment != null)은 더 하위 자식이 없으므로 기존처럼 하드 삭제를 유지한다.
	@Test
	void deleteCommentHardDeletesReplyWhenAuthenticatedUserIsAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment parent = PostComment.create(post, author, "parent", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parent, "id", 3L);
		PostComment reply = PostComment.create(post, author, "reply", parent, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		ReflectionTestUtils.setField(reply, "id", 9L);
		when(commentRepository.findById(9L)).thenReturn(Optional.of(reply));

		service.deleteComment(42L, 9L);

		verify(commentRepository).delete(reply);
		assertThat(reply.isTombstoned()).isFalse();
	}

	@Test
	void deleteCommentThrowsForbiddenAndDoesNotTombstoneTopLevelCommentWhenAuthenticatedUserIsNotAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment comment = PostComment.create(post, author, "comment", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		ReflectionTestUtils.setField(comment, "id", 9L);
		when(commentRepository.findById(9L)).thenReturn(Optional.of(comment));

		assertThatThrownBy(() -> service.deleteComment(999L, 9L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		assertThat(comment.isTombstoned()).isFalse();
		verify(commentRepository, never()).delete(any());
	}

	// 소유권 규칙 회귀 — 대댓글도 타인이 삭제를 시도하면 403이며, tombstone 도입으로 이 검증이 약해지지 않았는지 확인.
	@Test
	void deleteCommentThrowsForbiddenAndDoesNotDeleteReplyWhenAuthenticatedUserIsNotAuthor() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment parent = PostComment.create(post, author, "parent", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parent, "id", 3L);
		PostComment reply = PostComment.create(post, author, "reply", parent, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		ReflectionTestUtils.setField(reply, "id", 9L);
		when(commentRepository.findById(9L)).thenReturn(Optional.of(reply));

		assertThatThrownBy(() -> service.deleteComment(999L, 9L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		verify(commentRepository, never()).delete(any());
	}

	@Test
	void deleteCommentThrowsNotFoundAndDoesNotDeleteWhenCommentDoesNotExist() {
		when(commentRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteComment(42L, 404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(commentRepository, never()).delete(any());
	}

	@Test
	void getCommentsReturnsRepositoryResultsMappedInOriginalOrder() {
		User firstAuthor = User.create("first@finplay.com", "hash", "first", LocalDateTime.now(CLOCK));
		User secondAuthor = User.create("second@finplay.com", "hash", "second", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(firstAuthor, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment first = PostComment.create(
			post, firstAuthor, "first comment", null, LocalDateTime.now(CLOCK).minusMinutes(1));
		PostComment second = PostComment.create(post, secondAuthor, "second comment", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(first, "id", 11L);
		ReflectionTestUtils.setField(second, "id", 12L);
		when(postRepository.existsById(7L)).thenReturn(true);
		when(commentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc(7L))
			.thenReturn(List.of(first, second));

		List<PostCommentResponse> responses = service.getComments(7L);

		assertThat(responses).containsExactly(
			new PostCommentResponse(
				11L, "first", "first comment", LocalDateTime.now(CLOCK).minusMinutes(1), null, List.of()),
			new PostCommentResponse(12L, "second", "second comment", LocalDateTime.now(CLOCK), null, List.of()));
		verify(commentRepository).findAllByPostIdOrderByCreatedAtAscIdAsc(7L);
	}

	@Test
	void getCommentsReturnsEmptyListForExistingPostWithoutComments() {
		when(postRepository.existsById(7L)).thenReturn(true);
		when(commentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of());

		assertThat(service.getComments(7L)).isEmpty();
	}

	@Test
	void getCommentsGroupsRepliesUnderCorrectParentsPreservingOrderAndLeavesChildlessParentsEmpty() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "post", null, LocalDateTime.now(CLOCK));
		PostComment parentA = PostComment.create(post, author, "parent A", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(parentA, "id", 1L);
		PostComment childA1 = PostComment.create(
			post, author, "child A1", parentA, LocalDateTime.now(CLOCK).plusMinutes(1));
		ReflectionTestUtils.setField(childA1, "id", 3L);
		PostComment parentB = PostComment.create(
			post, author, "parent B", null, LocalDateTime.now(CLOCK).plusMinutes(2));
		ReflectionTestUtils.setField(parentB, "id", 2L);
		PostComment childA2 = PostComment.create(
			post, author, "child A2", parentA, LocalDateTime.now(CLOCK).plusMinutes(3));
		ReflectionTestUtils.setField(childA2, "id", 4L);
		PostComment childB1 = PostComment.create(
			post, author, "child B1", parentB, LocalDateTime.now(CLOCK).plusMinutes(4));
		ReflectionTestUtils.setField(childB1, "id", 5L);
		PostComment parentC = PostComment.create(
			post, author, "parent C without replies", null, LocalDateTime.now(CLOCK).plusMinutes(5));
		ReflectionTestUtils.setField(parentC, "id", 6L);
		when(postRepository.existsById(7L)).thenReturn(true);
		when(commentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc(7L))
			.thenReturn(List.of(parentA, childA1, parentB, childA2, childB1, parentC));

		List<PostCommentResponse> responses = service.getComments(7L);

		assertThat(responses).hasSize(3);
		assertThat(responses.get(0).commentId()).isEqualTo(1L);
		assertThat(responses.get(0).replies())
			.extracting(PostCommentResponse::commentId)
			.containsExactly(3L, 4L);
		assertThat(responses.get(1).commentId()).isEqualTo(2L);
		assertThat(responses.get(1).replies())
			.extracting(PostCommentResponse::commentId)
			.containsExactly(5L);
		assertThat(responses.get(2).commentId()).isEqualTo(6L);
		assertThat(responses.get(2).replies()).isEmpty();
	}

	@Test
	void getCommentsDoesNotQueryCommentsWhenPostDoesNotExist() {
		when(postRepository.existsById(404L)).thenReturn(false);

		assertThatThrownBy(() -> service.getComments(404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(commentRepository, never()).findAllByPostIdOrderByCreatedAtAscIdAsc(any());
	}

	@Test
	void getCommentsDeclaresReadOnlyTransaction() throws NoSuchMethodException {
		Transactional transactional = PostCommentService.class
			.getMethod("getComments", Long.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.readOnly()).isTrue();
	}
}
