// 사용자·시장별 튜토리얼 계좌의 get-or-create·리셋을 담당하는 서비스
package com.finplay.api.domain.account.service;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TutorialAccountService {

	private final TutorialAccountRepository tutorialAccountRepository;
	private final UserQueryService userQueryService;

	// 튜토리얼 진입(PracticeAttemptService.ensureAttempt)이 이미 사용자·시장 조합을 직렬화하는 트랜잭션 안에서
	// 함께 호출한다(설계 판단 — 계좌 생성 시점). SELECT ... FOR UPDATE로 조회하고, 없으면 초기값(1000만원·
	// realizedPnl 0원)으로 생성한 뒤 같은 잠금 조회로 재확인해 반환한다.
	@Transactional
	public TutorialAccount getOrCreateForUpdate(Long userId, Market market, LocalDateTime now) {
		return tutorialAccountRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseGet(() -> createAndReload(userId, market, now));
	}

	private TutorialAccount createAndReload(Long userId, Market market, LocalDateTime now) {
		User user = userQueryService.getUser(userId);
		tutorialAccountRepository.save(TutorialAccount.create(user, market, now));
		return tutorialAccountRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new IllegalStateException(
				"생성 직후 튜토리얼 계좌를 조회하지 못했습니다. userId=" + userId + ", market=" + market));
	}

	/**
	 * 잠금 없이 읽기만 한다(이슈 #502). 계좌를 <b>한 글자도 바꾸지 않는 응답</b>이 쓰는 경로다.
	 *
	 * <p>{@link #getOrCreateForUpdate}를 쓰면 무변경 응답이 계좌 행에 X 잠금을 걸어, attempt 잠금이
	 * 직렬화하지 못하는 트랜잭션(지정가 취소·정정, 예약 청산의 매도 정산)과 실제로 경합해 대기한다.
	 * 교착이 되지는 않는다 — 두 부류의 잠금 집합이 역순으로 겹치지 않기 때문이다(한쪽은 attempt를
	 * 잡고 계좌로 가고, 다른 쪽은 attempt를 아예 잡지 않는다). <b>"모든 경로가 attempt를 먼저 잡는다"는
	 * 아니다</b> — {@code LimitOrderCancelService}는 order → account → tutorial account 순으로만 잠근다.
	 * 다만 교착이 없다는 것과 대기가 없다는 것은 다르고, 사용자가 한 번 부르는 호출이 진행 중인 체결
	 * 뒤에 줄을 설 이유가 없다.
	 */
	@Transactional(readOnly = true)
	public Optional<TutorialAccount> find(Long userId, Market market) {
		return tutorialAccountRepository.findByUserIdAndMarket(userId, market);
	}

	// 재시작 훅(TUTORIAL-CASH-ISOL-006) — 존재하면 현금·예약 현금·realizedPnl을 같은 타이밍에 초기화하고,
	// 아직 없던 사용자면 get-or-create로 생성한다(생성 직후 값이 이미 초기값과 같아 reset() 호출은 멱등하다).
	@Transactional
	public void resetForUpdate(Long userId, Market market, LocalDateTime now) {
		TutorialAccount account = getOrCreateForUpdate(userId, market, now);
		account.reset(now);
	}
}
