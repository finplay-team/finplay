package com.finplay.api.domain.education.service;

import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.model.PracticeIntention;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeIntentionService {

	public static final String TUTORIAL_KEY = "INVESTMENT_PRACTICE_V1";
	public static final String COIN_TUTORIAL_KEY = "COIN_PRACTICE_V1";

	private final PracticeProgressRepository practiceProgressRepository;
	private final PracticeIntentionRepository practiceIntentionRepository;
	private final FavoriteService favoriteService;
	private final InstrumentService instrumentService;
	private final Clock clock;

	@Transactional
	public PracticeIntentionResponse createIntention(
		Long userId,
		PracticeIntentionCreateRequest request) {
		Instrument instrument = instrumentService.getInstrumentEntity(request.instrumentId());
		String tutorialKey = resolveTutorialKey(instrument.getMarket());
		LocalDateTime createdAt = LocalDateTime.now(clock);

		practiceProgressRepository.insertIfAbsent(userId, tutorialKey, createdAt);
		PracticeProgress progress = practiceProgressRepository
			.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)
			.orElseThrow(() -> {
				log.error("practice_progresses 행을 insertIfAbsent 직후 조회하지 못함 (userId={}, tutorialKey={})",
					userId, tutorialKey);
				return new BusinessException(ErrorCode.INTERNAL_ERROR);
			});
		if (progress.getStatus() == PracticeProgressStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}

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

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> TUTORIAL_KEY;
			case CRYPTO -> COIN_TUTORIAL_KEY;
		};
	}
}
