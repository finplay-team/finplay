// 튜토리얼 attempt를 잠그는 사용자 요청(진입·재시작·tick)의 교착 재시도 경계를 담당하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.market.domain.Market;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

/**
 * `practice_attempts`를 잠그는 <b>사용자 요청 세 개</b>의 교착 재시도 경계다 (이슈 #491 완료조건 2).
 *
 * <p><b>왜 별도 빈인가.</b> 재시도는 트랜잭션 <b>밖에서</b> 일어나야 한다 — 교착으로 롤백된 트랜잭션
 * 안에서 다시 호출하면 이미 rollback-only로 표시돼 있어 아무것도 커밋되지 않는다. 같은 클래스 안에서
 * 나눠 부르면 자기호출이라 프록시를 타지 않아 {@code @Transactional}이 적용되지 않는다. ADR-0028이
 * 같은 이유로 {@code OrderService}(재시도) → {@code OrderExecutionService}(트랜잭션)로 나눈 구조를
 * 그대로 따른다. 컨트롤러에 try-catch를 두지 않는 것은 코드 컨벤션(§예외 처리)이다.
 *
 * <p><b>왜 이 셋인가.</b> ADR-0028 §결정 2와 같은 판정이다 — 지정가 체결은 실패해도 주문이 PENDING으로
 * 남아 다음 틱이 다시 시도하지만, 이 셋은 실패하면 그 요청이 그대로 끝나고 <b>사용자에게 재시도 수단이
 * 없다</b>(진입·tick은 화면이 멎고, 재시작은 버튼이 먹히지 않는다). 반대로 종목 선택·프리셋 선택은
 * 사용자가 같은 버튼을 다시 누르면 되므로 여기 넣지 않았다.
 *
 * <p><b>왜 재시도가 안전한가.</b> 교착은 트랜잭션 <b>전체</b>가 롤백되므로 부분 커밋이 없다. 진입은 그
 * 자체가 멱등한 get-or-create이고, 재시작은 롤백되면 run 번호도 오르지 않은 상태이며, tick은 롤백되면
 * 대본 커서가 전진하지 않은 상태다 — 셋 다 같은 인자로 다시 불러도 이중 반영이 없다.
 *
 * <p><b>1회로 제한한다.</b> 이슈 #491에서 실제로 재현·확정한 원인(진입 경로의 S→X 승격)은
 * {@code PracticeAttemptService.ensureAttempt}에서 제거했으므로 이 재시도는 그 교착을 위한 것이 아니라
 * 남은 위험에 대한 그물이다. 재시도가 또 실패하면 원인을 감추지 않고 그대로 전파한다.
 *
 * <p><b>재시작·tick에 대해서는 교착을 재현하지 못했다는 것을 밝혀 둔다.</b> 이슈 #491 코멘트가 그 조합을
 * 두 번째 재현 경로로 등록했지만(프론트 8~32회 관측), 세 형태 × 8라운드 × 생성기 버전 2종을 수정 전
 * 코드로 돌려도 교착이 나지 않았다({@code PracticeAttemptRestartTickConcurrencyIntegrationTest}).
 * 두 경로 모두 attempt를 <b>가장 먼저</b> 잠그므로 같은 사용자면 그 지점에서 직렬화되고 다른 사용자면
 * 겹치는 자원이 없다 — 순환이 성립하지 않는다는 것이 코드와 실측이 함께 가리키는 결론이다. 그래도 그물을
 * 두는 것은 "재현하지 못했다"가 "발생하지 않는다"는 아니고, 프론트에는 실제 관측 기록이 있기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeAttemptDeadlockRetryService {

	private final PracticeAttemptService practiceAttemptService;
	private final PracticeAttemptRestartService practiceAttemptRestartService;
	private final PracticeAttemptChartService practiceAttemptChartService;

	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		return retryOnce("튜토리얼 진입", userId, market,
			() -> practiceAttemptService.ensureAttempt(userId, market));
	}

	public PracticeAttemptResponse restart(Long userId, Market market) {
		return retryOnce("튜토리얼 재시작", userId, market,
			() -> practiceAttemptRestartService.restart(userId, market));
	}

	public PracticeTutorialChartResponse tick(Long userId, Market market) {
		return retryOnce("튜토리얼 tick", userId, market,
			() -> practiceAttemptChartService.tick(userId, market));
	}

	// 람다가 감싸는 것은 모두 프록시를 거친 호출이므로 트랜잭션은 이 메서드 <b>바깥이 아니라 안쪽</b>에서
	// 열리고 닫힌다 — 재시도 시점에는 앞선 트랜잭션이 이미 롤백돼 끝나 있다.
	private <T> T retryOnce(String operation, Long userId, Market market, Supplier<T> action) {
		try {
			return action.get();
		} catch (CannotAcquireLockException deadlock) {
			log.warn("{} 중 교착 발생 — 1회 재시도한다. userId={}, market={}", operation, userId, market, deadlock);
			return action.get();
		}
	}
}
