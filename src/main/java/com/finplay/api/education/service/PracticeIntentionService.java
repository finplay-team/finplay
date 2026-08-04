// 실습 진행(DB)을 잠그고 그 트랜잭션 안에서 즐겨찾기(in-memory) 락까지 이어 잡아 매수 전 투자 의도를 기록하는 서비스
package com.finplay.api.education.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.domain.PracticeProgress;
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.education.repository.PracticeIntentionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0012(#193): {@code practice_intentions}는 DB가 아니라 {@link PracticeIntentionRepository}의 힙 메모리에
 * 저장한다. {@code practice_progresses}는 여전히 DB 행이므로 기존 {@code SELECT ... FOR UPDATE} 잠금을
 * 유지하고, favorite in-memory 락은 그 DB 트랜잭션이 열려 있는 동안 이어서 획득한다 — 잠금 순서는
 * plan.md대로 progress(DB) → favorite(in-memory)를 유지해 데드락을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeIntentionService {

	public static final String TUTORIAL_KEY = "INVESTMENT_PRACTICE_V1";

	private final PracticeProgressRepository practiceProgressRepository;
	private final PracticeIntentionRepository practiceIntentionRepository;
	private final FavoriteService favoriteService;
	private final InstrumentService instrumentService;
	private final Clock clock;

	@Transactional
	public PracticeIntentionResponse createIntention(
		Long userId,
		PracticeIntentionCreateRequest request) {
		// 종목 존재 확인만 목적이며 인메모리 모델은 instrumentId만 보관한다.
		instrumentService.getInstrumentEntity(request.instrumentId());
		LocalDateTime createdAt = LocalDateTime.now(clock);

		practiceProgressRepository.insertIfAbsent(userId, TUTORIAL_KEY, createdAt);
		// insertIfAbsent가 같은 트랜잭션에서 행을 보장하므로 이 조회는 항상 성공해야 한다 — 도달하면
		// 클라이언트에도 컨벤션에 맞는 공통 오류 형식으로 응답하되(원인 불명의 500이지만 형식은 지킨다),
		// GlobalExceptionHandler.handleBusinessException은 로깅하지 않으므로 여기서 직접 남긴다.
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, TUTORIAL_KEY)
			.orElseThrow(() -> {
				log.error("practice_progresses 행을 insertIfAbsent 직후 조회하지 못함 (userId={}, tutorialKey={})",
					userId, TUTORIAL_KEY);
				return new BusinessException(ErrorCode.INTERNAL_ERROR);
			});
		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		// progress(DB) 락이 걸린 이 트랜잭션 안에서 favorite(in-memory) 락을 이어 잡는다. action 안에서
		// favorite 존재 확인과 intention 저장을 함께 수행해 TOCTOU 없이 원자적으로 처리한다.
		return favoriteService.withFavoriteLock(userId, request.instrumentId(), () -> {
			if (!favoriteService.isFavorited(userId, request.instrumentId())) {
				throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
			}
			PracticeIntention intention = PracticeIntention.create(
				null,
				userId,
				request.instrumentId(),
				request.quantity(),
				request.stopLoss(),
				request.takeProfit(),
				createdAt);
			return PracticeIntentionResponse.from(practiceIntentionRepository.save(intention));
		});
	}
}
