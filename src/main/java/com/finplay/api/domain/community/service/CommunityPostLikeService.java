// 게시물 좋아요 표시·취소를 멱등하게 처리하는 서비스
package com.finplay.api.domain.community.service;

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
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityPostLikeService {

	private final CommunityPostRepository communityPostRepository;
	private final CommunityPostLikeRepository communityPostLikeRepository;
	private final UserQueryService userQueryService;
	private final Clock clock;

	// 본인 게시물에도 좋아요를 허용한다(spec 045 비즈니스 규칙) — 별도 차단 로직을 두지 않는다.
	@Transactional
	public CommunityPostLikeOutcome likePost(Long postId, Long authenticatedUserId) {
		// 트랜잭션의 첫 문장으로 게시물 행을 비관적 락으로 잡아 같은 게시물의 좋아요·취소를 직렬화한다 —
		// 락 획득 순서가 통일되므로 동시 요청이 InnoDB 데드락(CannotAcquireLockException)으로 500이 나던
		// 문제가 사라진다(PR #442 2차 리뷰). 데드락은 InnoDB가 트랜잭션을 이미 롤백한 뒤라 catch로 수습할 수 없다.
		// 이 앞에 어떤 조회도 넣지 말 것 — 비잠금 조회가 먼저 나가면 REPEATABLE_READ read view가 그 시점에
		// 열려, 아래 existsByPost_IdAndUser_Id가 앞선 트랜잭션의 커밋을 못 보고 stale해진다.
		CommunityPost post = communityPostRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		boolean alreadyLiked = communityPostLikeRepository.existsByPost_IdAndUser_Id(postId, authenticatedUserId);
		if (alreadyLiked) {
			CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, post.getLikeCount(), true);
			return new CommunityPostLikeOutcome(response, false);
		}

		User user = userQueryService.getUser(authenticatedUserId);
		// 중복 좋아요를 막는 건 위 행 락이다 — 락이 직렬화한 뒤의 existsBy...는 앞선 트랜잭션의 커밋을 보므로
		// (InnoDB에서 잠금 읽기는 consistent read view를 만들지 않는다) 유니크 제약 위반 경로로 들어오지 않는다.
		// 그래서 DataIntegrityViolationException을 잡지 않는다. 유니크 제약은 데이터 정합성의 최후 방어선으로
		// 남아 있어, 설령 뚫리더라도 중복 행이 조용히 생기는 대신 오류로 드러난다.
		communityPostLikeRepository.saveAndFlush(CommunityPostLike.create(post, user, LocalDateTime.now(clock)));
		// incrementLikeCount의 clearAutomatically가 영속성 컨텍스트를 비워 post를 준영속으로 만들기 전에
		// 값을 읽어 둔다.
		long likeCountBeforeIncrement = post.getLikeCount();
		communityPostRepository.incrementLikeCount(postId);

		CommunityPostLikeResponse response = new CommunityPostLikeResponse(postId, likeCountBeforeIncrement + 1, true);
		return new CommunityPostLikeOutcome(response, true);
	}

	@Transactional
	public void unlikePost(Long postId, Long authenticatedUserId) {
		// likePost와 같은 순서로 게시물 행을 먼저 잡는다 — 동시 취소 2건이 각자 존재를 확인한 뒤 감소 쿼리를
		// 실행해 like_count가 음수로 내려가던 문제와, 이미 삭제된 행을 다시 지우다 나던
		// ObjectOptimisticLockingFailureException을 함께 막는다(PR #442 2차 리뷰).
		communityPostRepository.findByIdForUpdate(postId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		communityPostLikeRepository.findByPost_IdAndUser_Id(postId, authenticatedUserId)
			.ifPresent(like -> {
				communityPostLikeRepository.delete(like);
				communityPostRepository.decrementLikeCount(postId);
			});
	}
}
