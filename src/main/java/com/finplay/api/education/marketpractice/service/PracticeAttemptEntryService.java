// 튜토리얼 진입(ensureAttempt)을 호출하고 잔여 교착에 1회만 재시도하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.market.domain.Market;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

/**
 * 진입 API의 교착 재시도 경계다 (이슈 #491 완료조건 2).
 *
 * <p><b>왜 별도 빈인가.</b> 재시도는 트랜잭션 <b>밖에서</b> 일어나야 한다 — 교착으로 롤백된 트랜잭션
 * 안에서 다시 호출하면 이미 rollback-only로 표시돼 있어 아무것도 커밋되지 않는다. 같은 클래스 안에서
 * 나눠 부르면 자기호출이라 프록시를 타지 않아 {@code @Transactional}이 적용되지 않는다. ADR-0028이
 * 같은 이유로 {@code OrderService}(재시도) → {@code OrderExecutionService}(트랜잭션)로 나눈 구조를
 * 그대로 따른다. 컨트롤러에 try-catch를 두지 않는 것은 코드 컨벤션(§예외 처리)이다.
 *
 * <p><b>재시도가 이 경로에만 있는 이유.</b> ADR-0028 §결정 2와 같은 판정이다 — 지정가 체결은 실패해도
 * 주문이 PENDING으로 남아 다음 틱이 다시 시도하지만, 진입은 실패하면 그 요청이 그대로 끝나고 사용자에게
 * 재시도 수단이 없다(화면이 로드되지 않는다). 교착은 트랜잭션 전체가 롤백되므로 부분 커밋이 없고,
 * {@code ensureAttempt}는 그 자체가 멱등한 get-or-create라 같은 인자로 다시 불러도 안전하다.
 *
 * <p><b>1회로 제한한다.</b> 이슈 #491의 원인(INSERT IGNORE의 S→X 승격)은
 * {@code PracticeAttemptRepository.insertIfAbsent}에서 제거했으므로 이 재시도는 그 교착을 위한 것이
 * 아니라 남은 위험(같은 트랜잭션이 함께 잠그는 tutorial_accounts·practice_progresses가 다른 경로와
 * 경합하는 경우)에 대한 그물이다. 재시도가 또 실패하면 원인을 감추지 않고 그대로 전파한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeAttemptEntryService {

	private final PracticeAttemptService practiceAttemptService;

	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		try {
			return practiceAttemptService.ensureAttempt(userId, market);
		} catch (CannotAcquireLockException deadlock) {
			log.warn("튜토리얼 진입 중 교착 발생 — 1회 재시도한다. userId={}, market={}", userId, market, deadlock);
			return practiceAttemptService.ensureAttempt(userId, market);
		}
	}
}
