// OCO 손절·익절 예약의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.order.repository;

import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExitPlanRepository extends JpaRepository<ExitPlan, Long> {

	// 본인 소유 예약 단건 조회 — 타인 소유는 존재를 숨겨 404로 응답하기 위해 userId를 조건에 포함한다(021 spec).
	Optional<ExitPlan> findByIdAndUserId(Long id, Long userId);

	// holding당 PENDING 1건 불변식 검증용(021 plan "holding당 PENDING 1건"). 호출부가 holding을 먼저
	// 비관 잠금하므로 이 조회 자체는 락을 걸지 않는다.
	boolean existsByHoldingIdAndStatus(Long holdingId, ExitPlanStatus status);

	List<ExitPlan> findByUserIdAndStatusOrderByIdDesc(Long userId, ExitPlanStatus status);

	// 사용자 취소(021 plan.md 잠금 순서 holding → plan) — 호출부가 holding을 먼저 잠근 뒤 이 plan을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM ExitPlan p WHERE p.id = :id")
	Optional<ExitPlan> findByIdForUpdate(@Param("id")
	Long id);

	// 042 EXITPRESET-008 — 매도 원인(손절·익절·수동) 역참조용. 상태와 무관하게 그 실행 세대의 예약을 전부
	// 읽는다 — 이미 체결·취소된 예약이어야 triggered_order_id가 채워져 있다.
	List<ExitPlan> findByPracticeAttemptIdAndPracticeAttemptRunNumber(Long practiceAttemptId,
		Long practiceAttemptRunNumber);

	// 042 EXITPRESET-014·015·016 — 현재 튜토리얼 실행 세대에 귀속된 PENDING 예약의 id만 읽는다.
	// 종목 단위인 findPendingExitPlansToFill을 쓰지 않는 이유는 그것이 다른 실행 세대·다른 사용자의 예약까지
	// 함께 잡기 때문이다. id만 프로젝션하는 것은 체결·취소 서비스가 각자 잠금 순서대로 다시 조회하기 때문이다.
	@Query("""
		select p.id from ExitPlan p
		where p.practiceAttemptId = :attemptId
		  and p.practiceAttemptRunNumber = :runNumber
		  and p.status = com.finplay.api.domain.order.entity.ExitPlanStatus.PENDING
		order by p.id asc
		""")
	List<Long> findPendingPracticeRunExitPlanIds(@Param("attemptId")
	Long attemptId, @Param("runNumber")
	long runNumber);

	// 가격 갱신 시 체결 후보 OCO 예약 조회(021 plan.md "가격 트리거"). 익절·손절 방향이 반대라 두 조건을 OR로
	// 묶는다 — 생성 시 0 < stopLossPrice < entryPrice < takeProfitPrice가 보장되므로 한 plan이 두 방향을 동시에
	// 만족하지 않는다(체결 서비스가 잠근 뒤 재판정한다, LimitOrderTriggerListener의 findPendingLimitOrdersToFill과
	// 동일 관례로 락 없이 후보만 조회).
	//
	// practiceAttemptId is null 조건으로 튜토리얼 자동 예약을 제외한다(findPendingLimitOrdersToFill의 030 역방향
	// 오염 차단과 같은 이유 — 실제 코인 시세 tick이 교육 예약을 체결하지 않는다). 튜토리얼 예약은 대본 canonical
	// 가격을 넘기는 PracticeOrderSettlementService.settleCurrentRun만 체결한다(042 EXITPRESET-013·014).
	// 이 조건이 없으면 SANDBOX_COIN_1도 market=CRYPTO·tradable=true라 BithumbFeedSimulator가 3초마다 주입하는
	// 합성 틱(10만~1000만원)이 CryptoPriceUpdatedEvent로 흘러와, tick을 한 번도 부르지 않은 예약을 즉시
	// 익절 체결시킨다(PR #487 리뷰 QA에서 3회 재현).
	@Query("""
		select p from ExitPlan p
		where p.instrument.id = :instrumentId and p.status = com.finplay.api.domain.order.entity.ExitPlanStatus.PENDING
		  and p.practiceAttemptId is null
		  and (p.takeProfitPrice <= :price or p.stopLossPrice >= :price)
		order by p.reservedAt asc, p.id asc
		""")
	List<ExitPlan> findPendingExitPlansToFill(@Param("instrumentId")
	Long instrumentId, @Param("price")
	BigDecimal price);
}
