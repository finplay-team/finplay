// OCO 생성 엔진 호출과 Idempotency-Key 매핑 저장을 한 트랜잭션으로 묶는 서비스
package com.finplay.api.order.service;

import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanIdempotencyKey;
import com.finplay.api.order.repository.ExitPlanIdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ExitPlanCreationService}는 021 plan.md "일반 경로 검증 순서" 3~9단계만 책임지고
 * {@code Idempotency-Key} 처리는 호출부 몫으로 남긴다. 이 서비스가 그 호출부 역할 중 "매핑 저장"만 맡아,
 * 생성(3~9단계)과 매핑 저장이 항상 한 트랜잭션에서 커밋되게 한다 — 021 plan.md "멱등성" 일반 경로 2번(생성
 * 시도)이 실패 없이 끝나면 반드시 매핑도 함께 남아야 다음 재시도가 선제 조회로 재현될 수 있다.
 *
 * <p>{@code (user_id, idempotency_key)} unique 위반은 이 트랜잭션 전체를 롤백시키는 {@code
 * DataIntegrityViolationException}으로 나타난다 — 호출부({@link ExitPlanService})가 이를 캐치해 1회 재조회
 * 폴백을 수행한다(OrderService.createOrder와 동일 패턴).
 */
@Service
@RequiredArgsConstructor
public class ExitPlanIdempotentCreationService {

	private final ExitPlanCreationService exitPlanCreationService;
	private final ExitPlanIdempotencyKeyRepository exitPlanIdempotencyKeyRepository;

	@Transactional
	public ExitPlan create(ExitPlanCreateCommandDto command, String idempotencyKey) {
		ExitPlan plan = exitPlanCreationService.create(command);
		exitPlanIdempotencyKeyRepository.save(
			ExitPlanIdempotencyKey.of(command.user(), idempotencyKey, command.requestHash(), plan,
				plan.getReservedAt()));
		return plan;
	}
}
