// InvestmentPracticeQueryService의 GET /api/education/practice 5가지 상태 판정을 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.portfolio.domain.Holding;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InvestmentPracticeQueryServiceTest {

	private static final Long USER_ID = 1L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final FavoriteService favoriteService = mock(FavoriteService.class);
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final ReferencePriceCalculator referencePriceCalculator = mock(ReferencePriceCalculator.class);
	private final PracticeMarketObservationRepository practiceMarketObservationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final PracticeCompletionRepository practiceCompletionRepository = mock(
		PracticeCompletionRepository.class);

	private final InvestmentPracticeQueryService service = new InvestmentPracticeQueryService(
		favoriteService, chainResolutionService, referencePriceCalculator, practiceMarketObservationRepository,
		practiceCompletionRepository);

	@Test
	void getProgressReturnsCompletedWithSharedEvidenceAcrossAllThreeStepsWhenCompletionExists() {
		Holding holding = holding(40L, 100L);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(10));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.tutorialKey()).isEqualTo(PracticeIntentionService.TUTORIAL_KEY);
		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.currentStep()).isNull();
		assertThat(response.completedAt()).isEqualTo(NOW);
		assertThat(response.steps()).hasSize(3);
		for (PracticeStepResponse step : response.steps()) {
			assertThat(step.status()).isEqualTo("COMPLETED");
			assertThat(step.locked()).isFalse();
			assertThat(step.evidence().favoriteId()).isEqualTo(10L);
			assertThat(step.evidence().intentionId()).isEqualTo(20L);
			assertThat(step.evidence().buyTradeId()).isEqualTo(30L);
			assertThat(step.evidence().holdingId()).isEqualTo(40L);
			assertThat(step.evidence().observationId()).isEqualTo(60L);
			assertThat(step.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
			assertThat(step.evidence().reflectionId()).isEqualTo(50L);
			assertThat(step.evidence().reflectionCreatedAt()).isEqualTo(reflection.getCreatedAt());
		}
		// 완료 후에는 참조 가격선을 계산하지 않는다.
		assertThat(response.steps().get(0).evidence().referenceStopLossPrice()).isNull();
		assertThat(response.steps().get(0).evidence().referenceTakeProfitPrice()).isNull();
	}

	@Test
	void getProgressKeepsHoldingObservationAndReflectionButNullsFavoriteIntentionBuyTradeWhenChainLostOnRestart() {
		Holding holding = holding(40L, 100L);
		PracticeMarketReflection reflection = reflection(50L, holding, NOW.minusMinutes(1));
		PracticeCompletion completion = completion(reflection, NOW);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(completion));

		// 재시작으로 favorite/intention이 유실돼 chain 해석이 실패한다(빈 값).
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L))
			.thenReturn(Optional.empty());

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.TIMED_REPETITION,
			NOW.minusMinutes(10));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("COMPLETED");
		PracticeStepResponse firstStep = response.steps().get(0);
		assertThat(firstStep.evidence().favoriteId()).isNull();
		assertThat(firstStep.evidence().favoriteCreatedAt()).isNull();
		assertThat(firstStep.evidence().intentionId()).isNull();
		assertThat(firstStep.evidence().intentionCreatedAt()).isNull();
		assertThat(firstStep.evidence().buyTradeId()).isNull();
		assertThat(firstStep.evidence().buyTradeExecutedAt()).isNull();
		// holding·observation·reflection은 그대로 유지된다 — 완료 판정 자체는 흔들리지 않는다.
		assertThat(firstStep.evidence().holdingId()).isEqualTo(40L);
		assertThat(firstStep.evidence().observationId()).isEqualTo(60L);
		assertThat(firstStep.evidence().evidenceType()).isEqualTo("TIMED_REPETITION");
		assertThat(firstStep.evidence().reflectionId()).isEqualTo(50L);
	}

	@Test
	void getProgressReturnsInProgressStepThreeWithObservationEvidenceWhenChainHasQualifyingObservation() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain))
			.thenReturn(Optional.of(new ReferencePriceLines(new BigDecimal("90.00000000"),
				new BigDecimal("110.00000000"))));

		PracticeMarketObservation qualifying = observation(60L, PracticeEvidenceType.CLOSER_TO_BOUNDARY,
			NOW.minusMinutes(5));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifying));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);
		assertThat(response.completedAt()).isNull();
		assertThat(response.steps()).hasSize(3);

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("COMPLETED");
		assertThat(step1.locked()).isFalse();
		assertThat(step1.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step1.evidence().intentionId()).isNull();

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("COMPLETED");
		assertThat(step2.evidence().intentionId()).isEqualTo(20L);
		assertThat(step2.evidence().buyTradeId()).isEqualTo(30L);
		assertThat(step2.evidence().holdingId()).isEqualTo(40L);
		assertThat(step2.evidence().referenceStopLossPrice()).isEqualByComparingTo("90.00000000");
		assertThat(step2.evidence().referenceTakeProfitPrice()).isEqualByComparingTo("110.00000000");
		assertThat(step2.evidence().observationId()).isNull();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("IN_PROGRESS");
		assertThat(step3.locked()).isFalse();
		assertThat(step3.evidence().holdingId()).isEqualTo(40L);
		assertThat(step3.evidence().observationId()).isEqualTo(60L);
		assertThat(step3.evidence().observationObservedAt()).isEqualTo(qualifying.getObservedAt());
		assertThat(step3.evidence().evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");
		assertThat(step3.evidence().reflectionId()).isNull();
	}

	@Test
	void getProgressReturnsInProgressStepThreeWithNullObservationFieldsWhenNoQualifyingObservationExists() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		ResolvedPracticeChainDto chain = chainDto(10L, NOW.minusDays(3), 20L, NOW.minusDays(2), 30L,
			NOW.minusDays(1), 40L);
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain))
			.thenReturn(Optional.of(new ReferencePriceLines(new BigDecimal("90.00000000"),
				new BigDecimal("110.00000000"))));

		// 관찰이 없거나(빈 리스트) evidenceType이 전부 null이면 qualifying observation이 없다.
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAscIdAsc(USER_ID, 40L))
			.thenReturn(List.of());

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(3);

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("IN_PROGRESS");
		// chain 필드는 그대로 채워지지만 observation 필드만 null이다.
		assertThat(step3.evidence().holdingId()).isEqualTo(40L);
		assertThat(step3.evidence().referenceStopLossPrice()).isEqualByComparingTo("90.00000000");
		assertThat(step3.evidence().observationId()).isNull();
		assertThat(step3.evidence().observationObservedAt()).isNull();
		assertThat(step3.evidence().evidenceType()).isNull();
	}

	@Test
	void getProgressReturnsInProgressStepTwoWithEarliestFavoriteWhenNoValidChainButFavoritesExistForMarket() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());

		FavoriteResponse laterFavorite = new FavoriteResponse(11L, 200L, "STOCK", "SYM2", "종목2", NOW.minusDays(1));
		FavoriteResponse earlierFavorite = new FavoriteResponse(10L, 100L, "STOCK", "SYM1", "종목1", NOW.minusDays(5));
		FavoriteResponse cryptoFavorite = new FavoriteResponse(12L, 300L, "CRYPTO", "SYM3", "종목3", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID))
			.thenReturn(new FavoriteListResponse(List.of(laterFavorite, earlierFavorite, cryptoFavorite)));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.currentStep()).isEqualTo(2);
		assertThat(response.completedAt()).isNull();

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("COMPLETED");
		assertThat(step1.locked()).isFalse();
		// 여러 favorite 중 가장 이른 것(earlierFavorite, favoriteId=10)을 대표로 쓴다. crypto는 제외.
		assertThat(step1.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step1.evidence().favoriteCreatedAt()).isEqualTo(earlierFavorite.createdAt());

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("IN_PROGRESS");
		assertThat(step2.locked()).isFalse();
		assertThat(step2.evidence().favoriteId()).isEqualTo(10L);
		assertThat(step2.evidence().intentionId()).isNull();
		assertThat(step2.evidence().buyTradeId()).isNull();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("NOT_STARTED");
		assertThat(step3.locked()).isTrue();
		assertThat(step3.evidence().favoriteId()).isNull();
	}

	@Test
	void getProgressReturnsNotStartedForAllStepsWhenNoCompletionChainOrFavoriteExists() {
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID,
			PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(chainResolutionService.resolve(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of()));

		InvestmentPracticeResponse response = service.getProgress(USER_ID, Market.CRYPTO);

		assertThat(response.tutorialKey()).isEqualTo(PracticeIntentionService.COIN_TUTORIAL_KEY);
		assertThat(response.status()).isEqualTo("NOT_STARTED");
		assertThat(response.currentStep()).isEqualTo(1);
		assertThat(response.completedAt()).isNull();

		PracticeStepResponse step1 = response.steps().get(0);
		assertThat(step1.status()).isEqualTo("NOT_STARTED");
		assertThat(step1.locked()).isFalse();
		assertThat(step1.evidence().favoriteId()).isNull();

		PracticeStepResponse step2 = response.steps().get(1);
		assertThat(step2.status()).isEqualTo("NOT_STARTED");
		assertThat(step2.locked()).isTrue();

		PracticeStepResponse step3 = response.steps().get(2);
		assertThat(step3.status()).isEqualTo("NOT_STARTED");
		assertThat(step3.locked()).isTrue();
	}

	private static ResolvedPracticeChainDto chainDto(
		Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt,
		Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long holdingId) {
		return new ResolvedPracticeChainDto(
			favoriteId, favoriteCreatedAt, intentionId, intentionCreatedAt, new BigDecimal("90"),
			new BigDecimal("110"), buyTradeId, buyTradeExecutedAt, new BigDecimal("100"), holdingId);
	}

	private static Holding holding(Long holdingId, Long instrumentId) {
		Instrument instrument = Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", instrumentId);
		Account account = Account.create(
			User.create("trader@finplay.com", "password-hash", "trader", NOW),
			com.finplay.api.account.domain.Market.STOCK, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", holdingId);
		return holding;
	}

	private static PracticeMarketReflection reflection(Long reflectionId, Holding holding, LocalDateTime createdAt) {
		PracticeMarketReflection reflection = PracticeMarketReflection.create(
			USER_ID, holding, PracticeIntentionService.TUTORIAL_KEY, (short)1, "복기 내용", createdAt);
		ReflectionTestUtils.setField(reflection, "id", reflectionId);
		return reflection;
	}

	private static PracticeCompletion completion(PracticeMarketReflection reflection, LocalDateTime completedAt) {
		return PracticeCompletion.create(USER_ID, PracticeIntentionService.TUTORIAL_KEY, reflection, completedAt);
	}

	private static PracticeMarketObservation observation(
		Long observationId, PracticeEvidenceType evidenceType, LocalDateTime observedAt) {
		PracticeMarketObservation observation = mock(PracticeMarketObservation.class);
		when(observation.getId()).thenReturn(observationId);
		when(observation.getEvidenceType()).thenReturn(evidenceType);
		when(observation.getObservedAt()).thenReturn(observedAt);
		return observation;
	}
}
