// ExitPlanService(일반 경로 오케스트레이터)의 필드 조합 검증·소유권/시장 검증·멱등성 판정을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanIdempotencyKey;
import com.finplay.api.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.order.dto.response.ExitPlanResponse;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.order.repository.ExitPlanIdempotencyKeyRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

	private final ExitPlanService service = new ExitPlanService(
		holdingService, userQueryService, exitPlanIdempotencyKeyRepository, exitPlanIdempotentCreationService);

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
			com.finplay.api.account.domain.Market.valueOf(market.name()), NOW);
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
