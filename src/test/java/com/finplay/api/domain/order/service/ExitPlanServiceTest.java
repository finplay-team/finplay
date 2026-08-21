// ExitPlanService(일반 경로 오케스트레이터)의 필드 조합 검증·소유권/시장 검증·멱등성 판정을 검증하는 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.domain.order.dto.response.ExitPlanListResponse;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanIdempotencyKey;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.repository.ExitPlanIdempotencyKeyRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class ExitPlanServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final Long USER_ID = 1L;
	private static final Long HOLDING_ID = 100L;
	private static final String IDEMPOTENCY_KEY = "11111111-1111-1111-1111-111111111111";

	private final HoldingService holdingService = mock(HoldingService.class);
	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository = mock(
		ExitPlanIdempotencyKeyRepository.class);
	private final ExitPlanIdempotentCreationService exitPlanIdempotentCreationService = mock(
		ExitPlanIdempotentCreationService.class);
	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanCancelService exitPlanCancelService = mock(ExitPlanCancelService.class);

	private final ExitPlanService service = new ExitPlanService(
		holdingService, userQueryService, exitPlanIdempotencyKeyRepository, exitPlanIdempotentCreationService,
		exitPlanRepository, exitPlanCancelService);

	@Test
	void createThrowsValidationErrorWhenIntentionIdProvided() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			1L, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenBuyTradeIdProvided() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, 5L, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenInstrumentIdProvided() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, 7L, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenHoldingIdMissing() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, null, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenQuantityIsZero() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, BigDecimal.ZERO, ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenQuantityIsNegative() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("-1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenExitPriceTypeMissing() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), null, null, null, null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenPriceModeMissingStopLoss() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			null, new BigDecimal("110000"), null, null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenPriceModeAlsoHasRateFields() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), new BigDecimal("5"), null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenPercentModeMissingRates() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PERCENT,
			null, null, new BigDecimal("5"), null);

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenPercentModeAlsoHasPriceFields() {
		ExitPlanCreateRequest request = new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PERCENT,
			new BigDecimal("95000"), null, new BigDecimal("5"), new BigDecimal("10"));

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsNotFoundWhenHoldingDoesNotExistOrIsNotOwnedByRequester() {
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.empty());
		ExitPlanCreateRequest request = priceModeRequest();

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);
		verifyNoInteractions(exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsValidationErrorWhenHoldingMarketIsStock() {
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		Holding stockHolding = holdingWithMarket(Market.STOCK);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(stockHolding));
		ExitPlanCreateRequest request = priceModeRequest();

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsTutorialInstrumentNotAllowedWhenHoldingInstrumentIsTutorialSample() {
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		Holding tutorialHolding = holdingWithMarket(Market.CRYPTO);
		ReflectionTestUtils.setField(tutorialHolding.getInstrument(), "tutorialSample", true);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(tutorialHolding));
		ExitPlanCreateRequest request = priceModeRequest();

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED);
		verifyNoInteractions(exitPlanIdempotentCreationService);
	}

	@Test
	void createReplaysExistingResponseWhenIdempotencyKeyMatchesSameRequestHash() {
		ExitPlanCreateRequest request = priceModeRequest();
		String matchingHash = ReflectionTestUtils.invokeMethod(service, "calculateRequestHash", request);
		Holding holding = holdingWithMarket(Market.CRYPTO);
		ExitPlan existingPlan = generalPlan(holding);
		ExitPlanIdempotencyKey mapping = ExitPlanIdempotencyKey.of(owner(), IDEMPOTENCY_KEY, matchingHash,
			existingPlan, NOW);
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(mapping));

		ExitPlanResponse response = service.create(USER_ID, IDEMPOTENCY_KEY, request);

		assertThat(response).isEqualTo(ExitPlanResponse.from(existingPlan));
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createThrowsIdempotencyConflictWhenIdempotencyKeyHashDiffers() {
		ExitPlanCreateRequest request = priceModeRequest();
		Holding holding = holdingWithMarket(Market.CRYPTO);
		ExitPlan existingPlan = generalPlan(holding);
		ExitPlanIdempotencyKey mapping = ExitPlanIdempotencyKey.of(owner(), IDEMPOTENCY_KEY, "different-hash",
			existingPlan, NOW);
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(mapping));

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
		verifyNoInteractions(holdingService, exitPlanIdempotentCreationService);
	}

	@Test
	void createCallsCreationServiceAndReturnsMappedResponseOnSuccess() {
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		Holding holding = holdingWithMarket(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		User user = owner();
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
		ExitPlan createdPlan = generalPlan(holding);
		when(exitPlanIdempotentCreationService.create(any(ExitPlanCreateCommandDto.class), eq(IDEMPOTENCY_KEY)))
			.thenReturn(createdPlan);
		ExitPlanCreateRequest request = priceModeRequest();

		ExitPlanResponse response = service.create(USER_ID, IDEMPOTENCY_KEY, request);

		assertThat(response).isEqualTo(ExitPlanResponse.from(createdPlan));
		verify(exitPlanIdempotentCreationService).create(any(ExitPlanCreateCommandDto.class), eq(IDEMPOTENCY_KEY));
	}

	@Test
	void createFallsBackToReplayWhenConcurrentIdempotencyKeyConstraintViolationOccurs() {
		Holding holding = holdingWithMarket(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(userQueryService.getUser(USER_ID)).thenReturn(owner());
		ExitPlan racedPlan = generalPlan(holding);
		ExitPlanCreateRequest request = priceModeRequest();
		String hash = ReflectionTestUtils.invokeMethod(service, "calculateRequestHash", request);
		ExitPlanIdempotencyKey mapping = ExitPlanIdempotencyKey.of(owner(), IDEMPOTENCY_KEY, hash, racedPlan, NOW);
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty(), Optional.of(mapping));
		when(exitPlanIdempotentCreationService.create(any(ExitPlanCreateCommandDto.class), eq(IDEMPOTENCY_KEY)))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-" + IDEMPOTENCY_KEY + "' for key 'uk_exit_plan_idempotency_keys_user_key'"));

		ExitPlanResponse response = service.create(USER_ID, IDEMPOTENCY_KEY, request);

		assertThat(response).isEqualTo(ExitPlanResponse.from(racedPlan));
	}

	@Test
	void createThrowsIdempotencyConflictWhenConcurrentConstraintViolationButReplayStillNotFound() {
		Holding holding = holdingWithMarket(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(userQueryService.getUser(USER_ID)).thenReturn(owner());
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		when(exitPlanIdempotentCreationService.create(any(ExitPlanCreateCommandDto.class), eq(IDEMPOTENCY_KEY)))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-" + IDEMPOTENCY_KEY + "' for key 'uk_exit_plan_idempotency_keys_user_key'"));
		ExitPlanCreateRequest request = priceModeRequest();

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	void createRethrowsUnrelatedDataIntegrityViolationExceptionWithoutTreatingItAsIdempotencyConflict() {
		Holding holding = holdingWithMarket(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(userQueryService.getUser(USER_ID)).thenReturn(owner());
		when(exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty());
		DataIntegrityViolationException unrelated = new DataIntegrityViolationException(
			"Duplicate entry '10-42' for key 'holdings.uk_holdings_account_instrument'");
		when(exitPlanIdempotentCreationService.create(any(ExitPlanCreateCommandDto.class), eq(IDEMPOTENCY_KEY)))
			.thenThrow(unrelated);
		ExitPlanCreateRequest request = priceModeRequest();

		assertThatThrownBy(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request))
			.isSameAs(unrelated);
		verify(exitPlanIdempotencyKeyRepository, times(1))
			.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
	}

	@Test
	void listDefaultsToPendingStatusWhenStatusOmitted() {
		Holding holding = holdingWithMarket(Market.CRYPTO);
		ExitPlan plan = generalPlan(holding);
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING))
			.thenReturn(List.of(plan));

		ExitPlanListResponse response = service.list(USER_ID, null);

		assertThat(response.content()).containsExactly(ExitPlanResponse.from(plan));
		verify(exitPlanRepository).findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING);
	}

	@Test
	void listUsesGivenStatusWhenProvided() {
		Holding holding = holdingWithMarket(Market.CRYPTO);
		ExitPlan plan = generalPlan(holding);
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.CANCELLED))
			.thenReturn(List.of(plan));

		ExitPlanListResponse response = service.list(USER_ID, ExitPlanStatus.CANCELLED);

		assertThat(response.content()).containsExactly(ExitPlanResponse.from(plan));
		verify(exitPlanRepository).findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.CANCELLED);
		verify(exitPlanRepository, times(0)).findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING);
	}

	@Test
	void listReturnsEmptyContentWhenNoMatchingPlans() {
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING))
			.thenReturn(List.of());

		ExitPlanListResponse response = service.list(USER_ID, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void listOnlyQueriesByRequestingUserIdSoOtherUsersPlansAreExcluded() {
		Long otherUserId = 999L;
		Holding holding = holdingWithMarket(Market.CRYPTO);
		ExitPlan plan = generalPlan(holding);
		// USER_ID(요청자) 소유 plan은 존재하지만, 다른 사용자(otherUserId)로 조회하면 리포지토리는 그 사용자
		// 소유 plan만 검색하므로 응답에 섞여 들어오지 않는다는 것을 검증한다.
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING))
			.thenReturn(List.of(plan));
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(otherUserId, ExitPlanStatus.PENDING))
			.thenReturn(List.of());

		ExitPlanListResponse response = service.list(otherUserId, null);

		assertThat(response.content()).isEmpty();
		verify(exitPlanRepository).findByUserIdAndStatusOrderByIdDesc(otherUserId, ExitPlanStatus.PENDING);
		verify(exitPlanRepository, times(0)).findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING);
	}

	// PR #487 리뷰 지적 — list의 튜토리얼 제외 필터와 cancel의 튜토리얼 거부에 테스트가 없었다.
	// 두 동작 모두 042 5번이 요구하는 안전장치라 회귀를 잡을 단위 테스트를 남긴다.
	@Test
	void listExcludesTutorialSamplePlanSoRealTradingScreenHasNoGhostReservation() {
		Holding tutorialHolding = holdingWithMarket(Market.CRYPTO);
		ReflectionTestUtils.setField(tutorialHolding.getInstrument(), "tutorialSample", true);
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING))
			.thenReturn(List.of(generalPlan(tutorialHolding)));

		ExitPlanListResponse response = service.list(USER_ID, null);

		assertThat(response.content()).isEmpty();
	}

	@Test
	void listKeepsRealInstrumentPlanWhenTutorialPlanCoexists() {
		Holding tutorialHolding = holdingWithMarket(Market.CRYPTO);
		ReflectionTestUtils.setField(tutorialHolding.getInstrument(), "tutorialSample", true);
		Holding realHolding = holdingWithMarket(Market.CRYPTO);
		ExitPlan realPlan = generalPlan(realHolding);
		when(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(USER_ID, ExitPlanStatus.PENDING))
			.thenReturn(List.of(generalPlan(tutorialHolding), realPlan));

		ExitPlanListResponse response = service.list(USER_ID, null);

		assertThat(response.content()).containsExactly(ExitPlanResponse.from(realPlan));
	}

	@Test
	void cancelThrowsTutorialInstrumentNotAllowedAndDoesNotReachEngineWhenPlanIsTutorialReservation() {
		Holding tutorialHolding = holdingWithMarket(Market.CRYPTO);
		ReflectionTestUtils.setField(tutorialHolding.getInstrument(), "tutorialSample", true);
		ExitPlan tutorialPlan = generalPlan(tutorialHolding);
		when(exitPlanRepository.findByIdAndUserId(tutorialPlan.getId(), USER_ID))
			.thenReturn(Optional.of(tutorialPlan));

		assertThatThrownBy(() -> service.cancel(USER_ID, tutorialPlan.getId()))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED);
		verifyNoInteractions(exitPlanCancelService);
	}

	@Test
	void cancelThrowsNotFoundWhenPlanIsMissingOrNotOwnedByRequester() {
		when(exitPlanRepository.findByIdAndUserId(500L, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancel(USER_ID, 500L))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_NOT_FOUND);
		verifyNoInteractions(exitPlanCancelService);
	}

	@Test
	void cancelDelegatesToEngineWhenPlanIsRealInstrumentReservation() {
		ExitPlan realPlan = generalPlan(holdingWithMarket(Market.CRYPTO));
		when(exitPlanRepository.findByIdAndUserId(realPlan.getId(), USER_ID)).thenReturn(Optional.of(realPlan));

		service.cancel(USER_ID, realPlan.getId());

		verify(exitPlanCancelService).cancel(USER_ID, realPlan.getId());
	}

	private static ExitPlanCreateRequest priceModeRequest() {
		return new ExitPlanCreateRequest(
			null, null, null, HOLDING_ID, new BigDecimal("1"), ExitPriceType.PRICE,
			new BigDecimal("95000"), new BigDecimal("110000"), null, null);
	}

	private static Holding holdingWithMarket(Market market) {
		Instrument instrument = Instrument.create(market, market == Market.CRYPTO ? "BTC" : "005930",
			market == Market.CRYPTO ? "비트코인" : "삼성전자", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		Account account = Account.create(owner(),
			Market.valueOf(market.name()), NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", HOLDING_ID);
		holding.applyBuy(new BigDecimal("10"), new BigDecimal("100000"), NOW);
		return holding;
	}

	private static ExitPlan generalPlan(Holding holding) {
		ExitPlan plan = ExitPlan.createGeneral(
			owner(), holding, holding.getInstrument(), new BigDecimal("1"), holding.getAveragePrice(),
			ExitPriceType.PRICE, null, null, new BigDecimal("95000"), new BigDecimal("110000"),
			new BigDecimal("100500"), NOW, "h".repeat(64), NOW);
		ReflectionTestUtils.setField(plan, "id", 500L);
		return plan;
	}

	private static User owner() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
