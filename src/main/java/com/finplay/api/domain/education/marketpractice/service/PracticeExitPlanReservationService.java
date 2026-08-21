// 사용자가 매도 화면에서 직접 거는 튜토리얼 손절·익절 예약의 생성과 그 가능 여부 판정을 담당하는 서비스 (052 EXITFREE-020)
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeExitExperienceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticePendingExitPlanResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.ExitPlanPracticeOriginDto;
import com.finplay.api.domain.order.service.ExitPriceInputDto;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunExitPlanSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 042는 손절·익절 예약을 <b>매수 체결 순간 서버가</b> 걸었다(EXITPRESET-004·012). 052 EXITFREE-020이 그
 * 순서를 뒤집어 <b>사용자가 매도 화면에서 직접</b> 걸게 한다 — 실전 화면의 순서(매수 → 보유 → 예약매도)와
 * 같아야 튜토리얼에서 익힌 조작이 실전에서 그대로 쓰이고, 무엇보다 "내가 손절선을 정해 둔다"는 행위 자체가
 * 이 튜토리얼이 가르치려는 것이기 때문이다.
 *
 * <p><b>바뀌는 것은 "언제 누가 만드는가" 하나다.</b> 042가 세운 나머지 불변식은 만들어진 예약이 attempt·실행
 * 세대로 귀속되기만 하면 그대로 성립한다 — tick 정산(EXITPRESET-014), 재시작의 예약 취소·수량 반환
 * (EXITPRESET-015), 수동 매도가 막히지 않음(EXITPRESET-016)은 모두
 * {@code ExitPlanRepository.findPendingPracticeRunExitPlanIds}로 그 실행 세대의 PENDING을 훑을 뿐 <b>누가
 * 만들었는지 묻지 않는다.</b>
 *
 * <p><b>기존 경로를 새로 만들지 않는다.</b> 가격선 계산·범위 검증·수량 예약·저장은 042 자동 예약이 쓰던
 * {@link ExitPlanCreationService}를 그대로 부르고(019 공식·엔진 4~9단계), 취소는 경로 공통인
 * {@code DELETE /api/exit-plans/{id}}가 이미 받는다. 비율 검증도
 * {@link com.finplay.api.education.marketpractice.dto.request.PracticeAttemptExitRatesUpdateRequest}와 같은
 * {@link ExitRates}가 한다.
 */
@Service
@RequiredArgsConstructor
public class PracticeExitPlanReservationService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final ExitPlanCreationService exitPlanCreationService;
	private final TradeService tradeService;
	private final HoldingService holdingService;
	private final Clock clock;

	/**
	 * 현재 실행 세대의 <b>보유 전량</b>에 대해 OCO 예약 하나를 만든다.
	 *
	 * <p><b>attempt를 먼저 잠근다.</b> 진입 순번 산출(write-once 판정)과 예약 생성이 같은 잠금 아래에 있어야
	 * 같은 사용자의 동시 요청 둘이 같은 진입에 예약 둘을 만들지 못한다 — 042 자동 예약이 매수 체결
	 * 트랜잭션에서 {@code findByIdForUpdate}로 직렬화한 것과 같은 형태다. 잠금 순서도 그때와 같은
	 * attempt → holding이라 이슈 #491류 교착을 새로 만들지 않는다.
	 *
	 * @param exitRates 구간·소수 자릿수는 요청 DTO의 Bean Validation과 {@link ExitRates} 생성자가 이미
	 *                  검증했다. {@code PUT .../exit-rates}와 <b>같은 규칙</b>이며 여기서 다시 판정하지 않는다
	 */
	@Transactional
	public ExitPlanResponse create(Long userId, Market market, ExitRates exitRates) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		ReservationState state = load(attempt);
		if (state.rejection() != null) {
			throw new BusinessException(state.rejection());
		}

		Holding holding = requireHolding(attempt);
		PracticeRiskSnapshot snapshot = state.snapshot();

		// 기준은 그 진입의 체결가다(052 EXITFREE-006). snapshot.entryPrice는 042가 이미 scale 8로 정규화해
		// 저장한 값이라 화면의 기준선과 실제 체결선이 하위 자리에서 갈리지 않는다 — 여기서 다시 정규화하면
		// 두 번 반올림된다.
		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPercent(
			snapshot.getEntryPrice(), exitRates.stopLossRate(), exitRates.takeProfitRate());
		ExitPlan plan = exitPlanCreationService.create(ExitPlanCreateCommandDto.practice(
			holding.getAccount().getUser(),
			holding,
			state.heldQuantity(),
			priceInput,
			state.requestHash(),
			new ExitPlanPracticeOriginDto(
				attempt.getId(),
				attempt.getRunNumber(),
				// 대본 canonical price를 넣는다 — 엔진 기본 경로는 샘플 종목이면 031의 벽시계 사인파로
				// 분기해 대본과 무관한 값을 baseline_price에 영속한다(042 plan §자동 예약 생성).
				canonicalPriceService.canonicalPrice(attempt, LocalDateTime.now(clock)))));
		return ExitPlanResponse.from(plan);
	}

	/**
	 * 진행 조회가 실을 예약 상태. <b>{@link #create}가 201을 줄 조건과 같은 산출식</b>을 쓴다 — 두 벌로
	 * 두면 화면이 열어 준 버튼이 서버에서 409로 거부된다(042가 {@code exitPresetLocked}에서 이미 겪은 갈림).
	 */
	@Transactional(readOnly = true)
	public PracticeExitPlanViewDto view(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return PracticeExitPlanViewDto.none();
		}
		ReservationState state = load(attempt);
		return new PracticeExitPlanViewDto(
			state.rejection() == null,
			PracticePendingExitPlanResponse.from(state.summary().pendingPlan()),
			PracticeExitExperienceResponse.from(state.summary()));
	}

	/**
	 * 생성 가능 여부를 한 번에 판정하고 생성에 필요한 값까지 함께 들고 나온다. {@code rejection}이
	 * {@code null}이면 생성 가능이다.
	 *
	 * <p><b>판정 순서가 곧 사용자에게 보이는 이유의 우선순위다.</b> 완료 → 진행 상태 → 경로 → 보유 →
	 * write-once 순이며, 049가 "앞 단계를 먼저 마쳐야 합니다"를 보유 잠금보다 앞에 둔 것과 같은 판단이다.
	 *
	 * <p><b>049의 단계 게이트를 여기서 다시 검사하지 않는다.</b> {@code PUT .../exit-rates}는 3단계 이전에도
	 * 부를 수 있어 게이트가 순서를 강제하는 유일한 수단이지만, 이 경로는 <b>3단계 대본을 쓰는 실행에서만</b>
	 * 열리고 그 대본으로 넘어가는 길이 {@code PracticeAttemptScriptAdvanceService} 하나뿐이며 그쪽이 이미
	 * 왕복 두 번을 요구한다 — 대본 식별자가 곧 게이트다. 여기에 게이트를 한 겹 더 두면, 판정이 어긋났을 때
	 * 3단계에 서서 포지션을 든 사용자가 예약을 영영 걸지 못하고 튜토리얼이 막힌다.
	 */
	private ReservationState load(PracticeAttempt attempt) {
		PracticeRunExitPlanSummaryDto summary = practiceExitPlanQueryService
			.summarizeCurrentRun(attempt.getId(), attempt.getRunNumber());
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.IN_PROGRESS || attempt.getInstrument() == null) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_STEP_LOCKED);
		}
		ErrorCode pathRejection = pathRejection(attempt);
		if (pathRejection != null) {
			return ReservationState.rejected(summary, pathRejection);
		}
		// 예약할 대상이 없으면 거부한다. 판정은 042 EXITPRESET-003의 프리셋 잠금·EXITPRESET-020의 진입
		// 가드와 **같은 산출식**(현재 실행 세대의 순보유수량)이라, 이전 실행에서 넘어온 보유가 이 실행의
		// 예약 대상으로 오판되지 않는다.
		BigDecimal heldQuantity = tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber());
		if (heldQuantity.signum() <= 0) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_STEP_LOCKED);
		}
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		if (snapshot == null) {
			// 보유가 있는데 진입 기준선이 없는 상태는 원장 불변식이 깨진 것이다(042 EXITPRESET-020이 매수
			// 체결과 같은 트랜잭션에서 만든다). 조용히 예약을 만들지 않고 드러낸다.
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		// 진입당 정확히 한 번(write-once, 052 EXITFREE-020). 판정은 **그 진입을 가리키는 감사 해시**로 하며
		// 상태를 묻지 않는다 — 취소된 예약도 세야 "취소하고 더 낮은 선으로 다시 걸기"가 막힌다(042
		// EXITPRESET-003이 막은 손절선 슬금슬금 내리기의 다른 문). 진입이 새로 열리면 해시가 달라져 그
		// 진입 몫으로 다시 한 번 열린다.
		//
		// **개수를 세는 판정으로 대신하지 않는다.** 예약 없이 지나간 진입이 하나라도 있으면 "예약 수 <
		// 진입 순번"이 남아 이미 예약한 진입에서 재생성이 조용히 다시 열린다.
		String requestHash = ExitPlanPracticeOriginDto.auditRequestHash(
			attempt.getId(), attempt.getRunNumber(), snapshot.getEntrySequence());
		if (practiceExitPlanQueryService.existsEntryReservation(
			attempt.getId(), attempt.getRunNumber(), requestHash)) {
			return ReservationState.rejected(summary, ErrorCode.EXIT_PLAN_ALREADY_EXISTS);
		}
		return new ReservationState(summary, heldQuantity, snapshot, requestHash, null);
	}

	/**
	 * 자동 예약과 사용자 주도 예약은 <b>같은 실행에 공존하지 않는다</b>(052 EXITFREE-020). 어느 쪽을 쓸지는
	 * 대본 식별자로 가른다 — 단계 진행 상태로 가르면 판정이 순환한다.
	 *
	 * <p>대본을 쓰지 않는 실행(생성기 버전 1·legacy과 대본이 없는 STOCK)은 042 그대로 매수 체결이 자동으로
	 * 걸므로 이 경로를 열지 않는다. 2단계 대본은 예약 자체를 두지 않기로 한 자리라(049 ORDERBASICS-022)
	 * 사용자 주도로도 열지 않는다 — ±12%를 가상 20분에 도는 그 대본에서 예약이 걸리면 자동 청산된 매도가
	 * 왕복으로 세지 않아 다음 단계가 영영 열리지 않는다. <b>2단계 대본이 늘어나면 이 판정도 함께 늘려야
	 * 한다.</b>
	 */
	private ErrorCode pathRejection(PracticeAttempt attempt) {
		TutorialScenarioScriptId scriptId = attempt.scenarioScriptId();
		if (scriptId == null) {
			return ErrorCode.PRACTICE_STEP_LOCKED;
		}
		return scriptId == TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1 ? ErrorCode.PRACTICE_STAGE_LOCKED : null;
	}

	private Holding requireHolding(PracticeAttempt attempt) {
		Long holdingId = holdingService
			.findHoldingId(attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		return holdingService.findHoldingForOwner(attempt.getUserId(), holdingId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}

	/** {@code rejection}이 {@code null}일 때만 나머지 세 값이 채워진다. */
	private record ReservationState(
		PracticeRunExitPlanSummaryDto summary,
		BigDecimal heldQuantity,
		PracticeRiskSnapshot snapshot,
		String requestHash,
		ErrorCode rejection) {

		static ReservationState rejected(PracticeRunExitPlanSummaryDto summary, ErrorCode rejection) {
			return new ReservationState(summary, null, null, null, rejection);
		}
	}
}
