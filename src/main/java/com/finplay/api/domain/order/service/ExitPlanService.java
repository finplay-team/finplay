// OCO 손절·익절 예약 생성 요청을 받아 일반 경로 검증·멱등성 판정 후 생성 엔진을 호출하는 오케스트레이터 서비스
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

/**
 * {@code ai/specs/021-general-risk-management-oco} plan.md "일반 경로 검증 순서" 1~2단계(holding 소유권·시장
 * 제한·샌드박스 종목 제외)와 "멱등성" 일반 경로 알고리즘을 담당한다. 3~9단계(잠금·예약·저장)는
 * {@link ExitPlanCreationService}에 위임한다.
 *
 * <p>{@code intentionId}를 지정하는 교육 경로는 이 이슈(#348) 범위가 아니다 — 후속 이슈("교육 경로 재접합")가
 * 채우기 전까지는 값이 오면 400 {@code VALIDATION_ERROR}로 명확히 거부한다(plan.md "라우트 재사용 결정").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExitPlanService {

	// ExitPlanIdempotentCreationService가 저장에 실패했을 때만 재조회 폴백을 태우기 위한 판별 근거(OrderService와
	// 동일한 근거 — V35 migration이 정의한 실제 unique 제약 이름).
	private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_exit_plan_idempotency_keys_user_key";

	private final HoldingService holdingService;
	private final UserQueryService userQueryService;
	private final ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository;
	private final ExitPlanIdempotentCreationService exitPlanIdempotentCreationService;
	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanCancelService exitPlanCancelService;

	// 021 plan.md "멱등성" 일반 경로: 선제 조회 → 검증 → 생성 시도 → unique 위반 캐치 → 1회 재조회 폴백.
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

	// 021 plan.md "응답 계약" — 본인 소유 예약만, status 생략 시 PENDING 기본값, 경로(일반/교육) 무관 공통 조회.
	//
	// **(042, 이슈 #477) 튜토리얼 자동 예약은 제외한다.** 042 전에는 create가 샌드박스 holding을 막아
	// 튜토리얼 exit_plan 행 자체가 없었는데, 042가 매수 체결 트랜잭션에서 처음으로 만든다. 그 예약이 걸린
	// holding은 033 SANDBOX-EXCL-001이 GET /api/holdings에서 이미 감추므로, 걸러 내지 않으면 실거래 화면에
	// **대응 보유가 없는 유령 예약**이 뜬다. 주문·체결 내역을 감추는 것과 같은 원칙이다.
	@Transactional(readOnly = true)
	public ExitPlanListResponse list(Long userId, ExitPlanStatus status) {
		ExitPlanStatus effectiveStatus = status != null ? status : ExitPlanStatus.PENDING;
		return ExitPlanListResponse.from(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(userId, effectiveStatus)
			.stream()
			.filter(plan -> !plan.getInstrument().isTutorialSample())
			.map(ExitPlanResponse::from)
			.toList());
	}

	/**
	 * 사용자 취소(021 RISK-OCO 취소 경로). <b>튜토리얼 자동 예약은 이 경로로 취소할 수 없다</b> —
	 * 그것을 허용하면 실거래 화면에서 튜토리얼 손절을 조용히 해제할 수 있고, 042가 tick 정산·재시작·매도
	 * 접수에서 관리하는 예약 생명주기가 밖에서 깨진다. 차단을 엔진({@link ExitPlanCancelService})이 아니라
	 * 이 호출부에 두는 것은 {@code create}의 샌드박스 차단과 같은 이유다(021 RISK-OCO-014) — 042의 내부
	 * 취소 경로는 엔진을 직접 부르므로 이 차단에 막히지 않는다.
	 */
	@Transactional
	public void cancel(Long userId, Long exitPlanId) {
		ExitPlan plan = exitPlanRepository.findByIdAndUserId(exitPlanId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND));
		if (plan.getInstrument().isTutorialSample()) {
			throw new BusinessException(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED);
		}
		exitPlanCancelService.cancel(userId, exitPlanId);
	}

	private void rejectUnsupportedEducationalPath(ExitPlanCreateRequest request) {
		if (request.intentionId() != null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "intentionId를 지정하는 교육 경로는 아직 지원하지 않습니다.");
		}
	}

	// 021 plan.md 필드 표 — 일반 경로는 holdingId·quantity·exitPriceType 필수, buyTradeId·instrumentId 금지.
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

	// 021 plan.md "일반 경로 검증 순서" 2단계 — 샌드박스 종목(투자 실습 튜토리얼 전용)은 intentionId 재접합
	// 전까지 일반 경로 대상이 아니다(이슈 #461, 047 spec TUTORIAL-CASH-ISOL-010 1안).
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

	// 기존 매핑을 찾으면 request hash를 비교해 과거 결과를 재현하거나(일치) 즉시 409(불일치)를 던진다.
	// 찾지 못하면 빈 Optional — 호출부가 신규 생성 경로로 진행한다(021 plan.md "멱등성" 일반 경로 1번).
	private Optional<ExitPlanResponse> findReplayResponse(Long userId, String idempotencyKey, String requestHash) {
		return exitPlanIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
			.map(mapping -> {
				if (!mapping.getRequestHash().equals(requestHash)) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return ExitPlanResponse.from(mapping.getExitPlan());
			});
	}

	// 021 plan.md "멱등성" — holdingId·quantity·exitPriceType + 타입별 가격/rate. quantity는
	// stripTrailingZeros().toPlainString()으로 정규화한다.
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
