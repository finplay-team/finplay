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
import com.finplay.api.favorite.repository.FavoriteRepository;
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
	private final FavoriteRepository favoriteRepository;
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
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, TUTORIAL_KEY)
			.orElseThrow(() -> new IllegalStateException("실습 진행 행을 생성한 뒤 조회할 수 없습니다."));
		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

		favoriteRepository.findByUserIdAndInstrumentIdForUpdate(userId, request.instrumentId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));

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
