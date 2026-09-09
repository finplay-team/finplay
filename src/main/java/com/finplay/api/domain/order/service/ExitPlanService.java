package com.finplay.api.domain.order.service;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.domain.order.dto.response.ExitPlanListResponse;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.repository.ExitPlanIdempotencyKeyRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExitPlanService {

	private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_exit_plan_idempotency_keys_user_key";

	private final HoldingService holdingService;
	private final UserQueryService userQueryService;
	private final ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository;
	private final ExitPlanIdempotentCreationService exitPlanIdempotentCreationService;
	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanCancelService exitPlanCancelService;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;

	public ExitPlanResponse create(Long userId, String idempotencyKey, ExitPlanCreateRequest request) {
		rejectUnsupportedEducationalPath(request);
		validateGeneralPathFieldCombination(request);

		String requestHash = calculateRequestHash(request);

		Optional<ExitPlanResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
		if (replay.isPresent()) {
			return replay.get();
		}

		Holding holding = holdingService.findHoldingForOwner(userId, request.holdingId())
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		validateMarketIsCrypto(holding);
		validateNotTutorialSample(holding);

		User user = userQueryService.getUser(userId);
		ExitPriceInputDto priceInput = buildPriceInput(request, holding.getAveragePrice());
		ExitPlanCreateCommandDto command = ExitPlanCreateCommandDto.general(user, holding, request.quantity(),
			priceInput, requestHash);

		try {
			ExitPlan plan = exitPlanIdempotentCreationService.create(command, idempotencyKey);
			return ExitPlanResponse.from(plan);
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			if (!isIdempotencyKeyConstraintViolation(concurrentDuplicate)) {
				throw concurrentDuplicate;
			}
			log.warn(
				"exit plan 저장 중 멱등키 제약 위반 발생 — 경합으로 간주해 재조회를 시도한다. userId={}, idempotencyKey={}",
				userId, idempotencyKey, concurrentDuplicate);
			return findReplayResponse(userId, idempotencyKey, requestHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		}
	}

	@Transactional(readOnly = true)
	public ExitPlanListResponse list(Long userId, ExitPlanStatus status) {
		ExitPlanStatus effectiveStatus = status != null ? status : ExitPlanStatus.PENDING;
		return ExitPlanListResponse.from(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(userId, effectiveStatus)
			.stream()
			.filter(plan -> !plan.getInstrument().isTutorialSample())
			.map(ExitPlanResponse::from)
			.toList());
	}

	@Transactional
	public void cancel(Long userId, Long exitPlanId) {
		ExitPlan plan = exitPlanRepository.findByIdAndUserId(exitPlanId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));
		if (plan.getInstrument().isTutorialSample() && isAutomaticPracticeReservation(plan)) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED);
		}
		exitPlanCancelService.cancel(userId, exitPlanId);
	}

	private boolean isAutomaticPracticeReservation(ExitPlan plan) {
		return practiceOrderAttributionPort.managesAutomaticExitPlans(
			plan.getPracticeAttemptId(), plan.getPracticeAttemptRunNumber());
	}

	private void rejectUnsupportedEducationalPath(ExitPlanCreateRequest request) {
		if (request.intentionId() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "intentionId를 지정하는 교육 경로는 아직 지원하지 않습니다.");
		}
	}

	private void validateGeneralPathFieldCombination(ExitPlanCreateRequest request) {
		if (request.buyTradeId() != null || request.instrumentId() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "일반 경로는 buyTradeId·instrumentId를 받지 않습니다.");
		}
		if (request.holdingId() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "holdingId는 필수입니다.");
		}
		if (request.quantity().signum() <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (request.exitPriceType() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "exitPriceType은 필수입니다.");
		}
		if (request.exitPriceType() == ExitPriceType.PRICE) {
			validatePriceModeFields(request);
		} else {
			validatePercentModeFields(request);
		}
	}

	private void validatePriceModeFields(ExitPlanCreateRequest request) {
		if (request.stopLoss() == null || request.takeProfit() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PRICE 방식은 stopLoss와 takeProfit이 모두 필요합니다.");
		}
		if (request.stopLossRate() != null || request.takeProfitRate() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
				"PRICE 방식은 stopLossRate·takeProfitRate를 가질 수 없습니다.");
		}
	}

	private void validatePercentModeFields(ExitPlanCreateRequest request) {
		if (request.stopLossRate() == null || request.takeProfitRate() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
				"PERCENT 방식은 stopLossRate와 takeProfitRate가 모두 필요합니다.");
		}
		if (request.stopLoss() != null || request.takeProfit() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PERCENT 방식은 stopLoss·takeProfit을 가질 수 없습니다.");
		}
	}

	private void validateMarketIsCrypto(Holding holding) {
		if (holding.getInstrument().getMarket() != Market.CRYPTO) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 종목만 일반 리스크관리 OCO를 지원합니다.");
		}
	}

	private void validateNotTutorialSample(Holding holding) {
		if (holding.getInstrument().isTutorialSample()) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED);
		}
	}

	private ExitPriceInputDto buildPriceInput(ExitPlanCreateRequest request, BigDecimal entryPrice) {
		return request.exitPriceType() == ExitPriceType.PRICE
			? ExitPriceInputDto.ofPrice(entryPrice, request.stopLoss(), request.takeProfit())
			: ExitPriceInputDto.ofPercent(entryPrice, request.stopLossRate(), request.takeProfitRate());
	}

	private boolean isIdempotencyKeyConstraintViolation(DataIntegrityViolationException exception) {
		Throwable cause = exception.getMostSpecificCause();
		return cause.getMessage() != null && cause.getMessage().contains(IDEMPOTENCY_KEY_CONSTRAINT_NAME);
	}

	private Optional<ExitPlanResponse> findReplayResponse(Long userId, String idempotencyKey, String requestHash) {
		return exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
			.map(mapping -> {
				if (!mapping.getRequestHash().equals(requestHash)) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return ExitPlanResponse.from(mapping.getExitPlan());
			});
	}

	private String calculateRequestHash(ExitPlanCreateRequest request) {
		String normalizedQuantity = request.quantity().stripTrailingZeros().toPlainString();
		String raw = request.exitPriceType() == ExitPriceType.PRICE
			? "{\"holdingId\":%d,\"quantity\":\"%s\",\"exitPriceType\":\"%s\",\"stopLoss\":\"%s\",\"takeProfit\":\"%s\"}"
				.formatted(request.holdingId(), normalizedQuantity, request.exitPriceType(),
					request.stopLoss().toPlainString(), request.takeProfit().toPlainString())
			: "{\"holdingId\":%d,\"quantity\":\"%s\",\"exitPriceType\":\"%s\",\"stopLossRate\":\"%s\",\"takeProfitRate\":\"%s\"}"
				.formatted(request.holdingId(), normalizedQuantity, request.exitPriceType(),
					request.stopLossRate().toPlainString(), request.takeProfitRate().toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
