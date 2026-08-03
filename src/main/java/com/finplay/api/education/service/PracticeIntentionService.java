// 실습 진행과 즐겨찾기를 잠근 뒤 매수 전 투자 의도를 기록하는 서비스
package com.finplay.api.education.service;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
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
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeIntentionService {

	public static final String TUTORIAL_KEY = "INVESTMENT_PRACTICE_V1";

	private final PracticeProgressRepository practiceProgressRepository;
	private final PracticeIntentionRepository practiceIntentionRepository;
	private final FavoriteService favoriteService;
	private final UserQueryService userQueryService;
	private final InstrumentService instrumentService;
	private final Clock clock;

	@Transactional
	public PracticeIntentionResponse createIntention(
		Long userId,
		PracticeIntentionCreateRequest request) {
		User user = userQueryService.getUser(userId);
		Instrument instrument = instrumentService.getInstrumentEntity(request.instrumentId());
		LocalDateTime createdAt = LocalDateTime.now(clock);

		practiceProgressRepository.insertIfAbsent(userId, TUTORIAL_KEY, createdAt);
		// insertIfAbsent가 같은 트랜잭션에서 행을 보장하므로 이 조회는 항상 성공해야 한다 — 도달하면
		// 클라이언트에도 컨벤션에 맞는 공통 오류 형식으로 응답한다(원인 불명의 500이지만 형식은 지킨다).
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, TUTORIAL_KEY)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		if (!favoriteService.lockFavoriteIfPresent(userId, request.instrumentId())) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}

		PracticeIntention intention = PracticeIntention.create(
			user,
			instrument,
			request.quantity(),
			request.stopLoss(),
			request.takeProfit(),
			createdAt);
		return PracticeIntentionResponse.from(practiceIntentionRepository.save(intention));
	}
}
