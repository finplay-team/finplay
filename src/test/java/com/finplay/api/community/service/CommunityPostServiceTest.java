// 인증 사용자 기반 게시글 생성 규칙과 저장 실패 경계를 검증하는 단위 테스트다.
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
import com.finplay.api.community.domain.CommunityPostImage;
import com.finplay.api.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.community.dto.response.CommunityPostListResponse;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.repository.PostCommentRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class CommunityPostServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T03:04:05Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final CommunityPostRepository repository = Mockito.mock(CommunityPostRepository.class);
	private final PostCommentRepository postCommentRepository = Mockito.mock(PostCommentRepository.class);
	private final UserQueryService userQueryService = Mockito.mock(UserQueryService.class);
	private final InstrumentService instrumentService = Mockito.mock(InstrumentService.class);
	private final CommunityPostImageService communityPostImageService = Mockito.mock(CommunityPostImageService.class);
	private final CommunityPostService service = new CommunityPostService(
		repository, postCommentRepository, userQueryService, instrumentService, communityPostImageService, CLOCK);

	private static Instrument instrument(Long id) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000L, true, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	@Test
	void createPostSavesAuthenticatedUserAndFixedCreationTime() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CommunityPostResponse response = service.createPost(42L, "title", "content", null, null);

		ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
		verify(repository).save(postCaptor.capture());
		assertThat(postCaptor.getValue().getAuthor()).isSameAs(author);
		assertThat(response.authorNickname()).isEqualTo("author");
		assertThat(response.title()).isEqualTo("title");
		assertThat(response.content()).isEqualTo("content");
		assertThat(response.createdAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response.updatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response.instrumentId()).isNull();
		assertThat(response.instrumentSymbol()).isNull();
		assertThat(response.instrumentName()).isNull();
		assertThat(response.imageId()).isNull();
		assertThat(response.imageUrl()).isNull();
		verifyNoInteractions(instrumentService);
		verifyNoInteractions(communityPostImageService);
	}

	@Test
	void createPostDoesNotSaveWhenAuthenticatedUserDoesNotExist() {
		when(userQueryService.getUser(404L))
			.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

		assertThatThrownBy(() -> service.createPost(404L, "title", "content", null, null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);
		verify(repository, never()).save(any());
	}

	@Test
	void createPostTagsInstrumentWhenInstrumentIdProvided() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		Instrument instrument = instrument(9L);
		when(instrumentService.getTradableInstrumentEntity(9L)).thenReturn(instrument);
		when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CommunityPostResponse response = service.createPost(42L, "title", "content", 9L, null);

		ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
		verify(repository).save(postCaptor.capture());
		assertThat(postCaptor.getValue().getInstrument()).isSameAs(instrument);
		assertThat(response.instrumentId()).isEqualTo(9L);
		assertThat(response.instrumentSymbol()).isEqualTo("BTC");
		assertThat(response.instrumentName()).isEqualTo("비트코인");
	}

	@Test
	void createPostFailsWithValidationErrorAndDoesNotSaveWhenInstrumentIsNotTradable() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(instrumentService.getTradableInstrumentEntity(999L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));

		assertThatThrownBy(() -> service.createPost(42L, "title", "content", 999L, null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verify(repository, never()).save(any());
	}

	@Test
	void createPostAssignsImageToSavedPostWhenImageIdProvided() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		CommunityPostImage image = Mockito.mock(CommunityPostImage.class);
		when(image.getId()).thenReturn(5L);
		when(communityPostImageService.resolveImageForPost(42L, 5L)).thenReturn(image);
		when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CommunityPostResponse response = service.createPost(42L, "title", "content", null, 5L);

		ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
		verify(repository).save(postCaptor.capture());
		InOrder inOrder = Mockito.inOrder(repository, image);
		inOrder.verify(repository).save(any(CommunityPost.class));
		inOrder.verify(image).assignToPost(postCaptor.getValue());
		// 소유 측(assignToPost)만 갱신되고 저장된 게시물의 역방향 image 필드가 동기화되지 않으면
		// 생성 응답의 imageId/imageUrl이 null로 나가는 회귀(양방향 동기화 누락)를 잡는 단정.
		assertThat(response.imageId()).isEqualTo(5L);
		assertThat(response.imageUrl()).isEqualTo(CommunityPostImageResponse.toImageUrl(5L));
	}

	@Test
	void createPostDoesNotAssignImageOrFailWhenImageIdIsNullForBackwardCompatibility() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(repository.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CommunityPostResponse response = service.createPost(42L, "title", "content", null, null);

		assertThat(response.imageId()).isNull();
		assertThat(response.imageUrl()).isNull();
		verifyNoInteractions(communityPostImageService);
	}

	@Test
	void createPostFailsWithNotFoundAndDoesNotSaveWhenImageDoesNotExist() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(communityPostImageService.resolveImageForPost(42L, 404L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> service.createPost(42L, "title", "content", null, 404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);
		verify(repository, never()).save(any());
	}

	@Test
	void createPostFailsWithForbiddenAndDoesNotSaveWhenImageBelongsToAnotherUser() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(communityPostImageService.resolveImageForPost(42L, 5L))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "본인이 업로드한 이미지만 사용할 수 있습니다."));

		assertThatThrownBy(() -> service.createPost(42L, "title", "content", null, 5L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);
		verify(repository, never()).save(any());
	}

	@Test
	void createPostFailsWithValidationErrorAndDoesNotSaveWhenImageIsAlreadyAssigned() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		when(userQueryService.getUser(42L)).thenReturn(author);
		when(communityPostImageService.resolveImageForPost(42L, 5L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "이미 다른 게시물에 사용된 이미지입니다."));

		assertThatThrownBy(() -> service.createPost(42L, "title", "content", null, 5L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verify(repository, never()).save(any());
	}

	@Test
	void getPostReturnsEveryFieldFromPostFoundByExactId() {
		User author = User.create("reader@finplay.com", "hash", "reader", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(
			author, "detail title", "detail content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		CommunityPostResponse response = service.getPost(73L);

		assertThat(response.postId()).isEqualTo(73L);
		assertThat(response.authorNickname()).isEqualTo("reader");
		assertThat(response.title()).isEqualTo("detail title");
		assertThat(response.content()).isEqualTo("detail content");
		assertThat(response.createdAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(response.updatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		verify(repository).findById(73L);
	}

	@Test
	void getPostFailsWithNotFoundWhenPostDoesNotExist() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getPost(404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(repository).findById(404L);
	}

	@Test
	void updatePostUpdatesFieldsAndUpdatedAtWhenAuthorMatches() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", null, createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		CommunityPostResponse response = service.updatePost(42L, 73L, "new title", "new content", true, null);

		assertThat(response.postId()).isEqualTo(73L);
		assertThat(response.authorNickname()).isEqualTo("author");
		assertThat(response.title()).isEqualTo("new title");
		assertThat(response.content()).isEqualTo("new content");
		assertThat(response.createdAt()).isEqualTo(createdAt);
		assertThat(response.updatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		assertThat(post.getTitle()).isEqualTo("new title");
		assertThat(post.getContent()).isEqualTo("new content");
		assertThat(response.instrumentId()).isNull();
		verifyNoInteractions(instrumentService);
	}

	@Test
	void updatePostTagsInstrumentWhenInstrumentIdProvided() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", null, createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));
		Instrument instrument = instrument(9L);
		when(instrumentService.getTradableInstrumentEntity(9L)).thenReturn(instrument);

		CommunityPostResponse response = service.updatePost(42L, 73L, "new title", "new content", true, 9L);

		assertThat(post.getInstrument()).isSameAs(instrument);
		assertThat(response.instrumentId()).isEqualTo(9L);
		assertThat(response.instrumentSymbol()).isEqualTo("BTC");
		assertThat(response.instrumentName()).isEqualTo("비트코인");
	}

	@Test
	void updatePostDetachesInstrumentWhenInstrumentIdKeyIsExplicitlyNullOnAlreadyTaggedPost() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", instrument(9L), createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		CommunityPostResponse response = service.updatePost(42L, 73L, "new title", "new content", true, null);

		assertThat(post.getInstrument()).isNull();
		assertThat(response.instrumentId()).isNull();
		assertThat(response.instrumentSymbol()).isNull();
		assertThat(response.instrumentName()).isNull();
		verifyNoInteractions(instrumentService);
	}

	@Test
	void updatePostPreservesInstrumentWhenInstrumentIdKeyIsAbsentOnAlreadyTaggedPost() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		Instrument instrument = instrument(9L);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", instrument, createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		CommunityPostResponse response = service.updatePost(42L, 73L, "new title", "new content", false, null);

		assertThat(post.getInstrument()).isSameAs(instrument);
		assertThat(response.instrumentId()).isEqualTo(9L);
		assertThat(response.instrumentSymbol()).isEqualTo("BTC");
		assertThat(response.instrumentName()).isEqualTo("비트코인");
		verifyNoInteractions(instrumentService);
	}

	@Test
	void updatePostFailsWithValidationErrorAndLeavesPostUnchangedWhenInstrumentIsNotTradable() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", null, createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));
		when(instrumentService.getTradableInstrumentEntity(999L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));

		assertThatThrownBy(() -> service.updatePost(42L, 73L, "new title", "new content", true, 999L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		assertThat(post.getTitle()).isEqualTo("old title");
		assertThat(post.getContent()).isEqualTo("old content");
		assertThat(post.getInstrument()).isNull();
	}

	@Test
	void updatePostFailsWithNotFoundWhenPostDoesNotExist() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.updatePost(42L, 404L, "new title", "new content", false, null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(repository).findById(404L);
	}

	@Test
	void updatePostFailsWithForbiddenAndLeavesPostUnchangedWhenAuthorDiffers() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		CommunityPost post = CommunityPost.create(author, "old title", "old content", null, createdAt);
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		assertThatThrownBy(() -> service.updatePost(999L, 73L, "new title", "new content", false, null))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		assertThat(post.getTitle()).isEqualTo("old title");
		assertThat(post.getContent()).isEqualTo("old content");
		assertThat(post.getUpdatedAt()).isEqualTo(createdAt);
	}

	@Test
	void getPostsMapsRepositoryPageToListResponseWithPageMetadata() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		Page<CommunityPost> page = new PageImpl<>(List.of(post), PageRequest.of(0, 10), 1);
		when(repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10), null)).thenReturn(page);

		CommunityPostListResponse response = service.getPosts(0, 10, null);

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
	void getPostsPassesInstrumentIdToRepositoryWhenProvided() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		Instrument instrument = instrument(9L);
		CommunityPost post = CommunityPost.create(
			author, "tagged title", "content", instrument, LocalDateTime.now(CLOCK));
		Page<CommunityPost> page = new PageImpl<>(List.of(post), PageRequest.of(0, 10), 1);
		when(repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10), 9L)).thenReturn(page);

		CommunityPostListResponse response = service.getPosts(0, 10, 9L);

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).instrumentId()).isEqualTo(9L);
		verify(repository).findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10), 9L);
	}

	@Test
	void getPostsReturnsEmptyContentWhenNoPostsExist() {
		Page<CommunityPost> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
		when(repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10), null)).thenReturn(emptyPage);

		CommunityPostListResponse response = service.getPosts(0, 10, null);

		assertThat(response.content()).isEmpty();
		assertThat(response.totalElements()).isEqualTo(0);
		assertThat(response.totalPages()).isEqualTo(0);
	}

	@Test
	void deletePostDeletesPostWithoutCommentsWhenAuthorMatches() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		service.deletePost(42L, 73L);

		verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNotNull(73L);
		verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNull(73L);
		verify(repository).delete(post);
	}

	// V31에서 parent_comment_id FK가 RESTRICT로 바뀌어 자식(대댓글)을 부모보다 먼저 지워야 한다 —
	// 순서가 뒤바뀌면 실제 DB에서 FK 위반이 나므로(이슈 #277 회귀), 두 삭제 호출의 순서까지 검증한다.
	@Test
	void deletePostDeletesChildCommentsBeforeParentCommentsBeforePostWhenPostHasComments() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		service.deletePost(42L, 73L);

		InOrder inOrder = Mockito.inOrder(postCommentRepository, repository);
		inOrder.verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNotNull(73L);
		inOrder.verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNull(73L);
		inOrder.verify(repository).delete(post);
	}

	@Test
	void deletePostCleansUpImageBeforeDeletingPostWhenPostHasImage() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		CommunityPostImage image = Mockito.mock(CommunityPostImage.class);
		ReflectionTestUtils.setField(post, "image", image);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		service.deletePost(42L, 73L);

		InOrder inOrder = Mockito.inOrder(postCommentRepository, communityPostImageService, repository);
		inOrder.verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNotNull(73L);
		inOrder.verify(postCommentRepository).deleteByPost_IdAndParentCommentIsNull(73L);
		inOrder.verify(communityPostImageService).deleteImageIfPresent(post);
		inOrder.verify(repository).delete(post);
	}

	@Test
	void deletePostStillDelegatesImageCleanupWhenPostHasNoImage() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		service.deletePost(42L, 73L);

		verify(communityPostImageService).deleteImageIfPresent(post);
		verify(repository).delete(post);
	}

	@Test
	void deletePostFailsWithNotFoundAndDoesNotDeleteWhenPostDoesNotExist() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deletePost(42L, 404L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		verify(postCommentRepository, never()).deleteByPost_IdAndParentCommentIsNotNull(any());
		verify(postCommentRepository, never()).deleteByPost_IdAndParentCommentIsNull(any());
		verify(repository, never()).delete(any());
	}

	@Test
	void deletePostFailsWithForbiddenAndDoesNotDeleteWhenAuthorDiffers() {
		User author = User.create("author@finplay.com", "hash", "author", LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(author, "id", 42L);
		CommunityPost post = CommunityPost.create(author, "title", "content", null, LocalDateTime.now(CLOCK));
		ReflectionTestUtils.setField(post, "id", 73L);
		when(repository.findById(73L)).thenReturn(Optional.of(post));

		assertThatThrownBy(() -> service.deletePost(999L, 73L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);

		verify(postCommentRepository, never()).deleteByPost_IdAndParentCommentIsNotNull(any());
		verify(postCommentRepository, never()).deleteByPost_IdAndParentCommentIsNull(any());
		verify(repository, never()).delete(any());
	}
}
