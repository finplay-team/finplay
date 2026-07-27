// 댓글 생성 서비스의 연관관계, 고정 시각, 실패 시 부수 효과를 검증하는 단위 테스트다.
package com.finplay.api.community.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.finplay.api.community.domain.PostComment;
import com.finplay.api.community.dto.response.PostCommentResponse;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

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
		CommunityPost post = CommunityPost.create(author, "title", "post", LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> {
			PostComment comment = invocation.getArgument(0);
			ReflectionTestUtils.setField(comment, "id", 9L);
			return comment;
		});

		PostCommentResponse response = service.createComment(7L, 42L, "comment");

		ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
		verify(commentRepository).save(captor.capture());
		assertThat(captor.getValue().getPost()).isSameAs(post);
		assertThat(captor.getValue().getAuthor()).isSameAs(author);
		assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response).isEqualTo(new PostCommentResponse(
			9L, "author", "comment", LocalDateTime.now(CLOCK)));
	}

	@Test
	void createCommentDoesNotLookupUserOrSaveWhenPostDoesNotExist() {
		when(postRepository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createComment(404L, 42L, "comment"))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verifyNoInteractions(userQueryService);
		verify(commentRepository, never()).save(any());
	}

	@Test
	void createCommentDoesNotSaveWhenAuthenticatedUserDoesNotExist() {
		User postAuthor = User.create("post@finplay.com", "hash", "poster", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(postAuthor, "title", "post", LocalDateTime.now(CLOCK));
		when(postRepository.findById(7L)).thenReturn(Optional.of(post));
		when(userQueryService.getUser(404L)).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		assertThatThrownBy(() -> service.createComment(7L, 404L, "comment"))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);

		verify(commentRepository, never()).save(any());
	}
}
