// 회원가입 시 STOCK·CRYPTO 초기 계좌를 같은 시각에 생성하는 서비스
package com.finplay.api.account.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.service.HoldingValuationDto;
import com.finplay.api.portfolio.service.HoldingValuationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

	// 시드머니가 원 단위 정수(long)라 1원 변화도 seedMoney(현재 1,000만원) 대비 소수 7자리에서 처음 나타난다
	// (1 ÷ 10,000,000 = 0.0000001) — scale 4로는 소액 손익이 반올림으로 0%가 되는 문제가 있어 8로 올렸다
	// (이슈 #390).
	//
	// seedMoney(Account.INITIAL_SEED_MONEY = 10,000,000 = 2^7 × 5^7)가 소인수 2·5로만 이뤄져 있어,
	// 어떤 원 단위 정수를 나누더라도 소수 7자리 안에서 나눗셈이 끝난다 — 그래서 scale 8의
	// RoundingMode.HALF_UP은 지금은 실제로 반올림을 수행할 상황 자체가 없다(PR #393 리뷰 참고).
	// seedMoney가 고정값이 아니게 되면(예: 튜토리얼 보상 등으로 가변화) 이 전제가 깨지므로,
	// 그 시점에 실제 반올림이 일어나는 케이스로 테스트를 다시 검증해야 한다.
	private static final int RETURN_RATE_SCALE = 8;

	private final AccountRepository accountRepository;
	private final HoldingValuationService holdingValuationService;
	private final Clock clock;

	@Transactional
	public void createAccountsFor(User user) {
		LocalDateTime now = LocalDateTime.now(clock);
		accountRepository.saveAll(List.of(
			Account.create(user, Market.STOCK, now),
			Account.create(user, Market.CRYPTO, now)));
	}

	@Transactional(readOnly = true)
	public Account getAccountFor(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarket(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 내 랭킹 조회(RankingService.getMyRanking)용 — User를 fetch join으로 함께 로딩해, 트랜잭션이 끝난 뒤에도
	// account.getUser().getNickname()에 안전하게 접근할 수 있게 한다(PR #234 리뷰 권장 반영). getAccountFor와
	// 달리 이 트랜잭션 안에서 Redis 호출을 하지 않는다 — DB 커넥션 점유 시간을 fetch join 조회 자체로 한정한다.
	@Transactional(readOnly = true)
	public Account getAccountForWithUser(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarketFetchUser(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 지정가 매수 생성 시 계좌를 잠가 동시 현금 예약 경합을 막는다(015-limit-order LMT-001).
	// 다른 도메인 서비스가 AccountRepository를 직접 주입하지 않도록 이 메서드만 거치게 한다(ADR-0002).
	@Transactional
	public Account getAccountForUpdate(Long userId, Market market) {
		return accountRepository
			.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	}

	// 지정가 체결 시 계좌를 잠근다(015-limit-order LMT-002, 잠금 순서 order → account → holding의 두 번째 단계).
	// 주문에 연결된 계좌는 FK로 항상 존재해야 하므로 없으면 원장 불변식 위반으로 보고 방어적으로 예외를 던진다
	// (호출부 리스너가 건별 catch로 흡수한다). 다른 도메인 서비스가 AccountRepository를 직접 주입하지 않게 한다(ADR-0002).
	@Transactional
	public Account getAccountByIdForUpdate(Long accountId) {
		return accountRepository
			.findByIdForUpdate(accountId)
			.orElseThrow(() -> new IllegalStateException("체결 대상 계좌를 찾을 수 없습니다. accountId=" + accountId));
	}

	// 랭킹 점수 갱신(RankingService.refreshScore)이 존재하지 않을 수도 있는 accountId를 조회할 때 쓴다.
	@Transactional(readOnly = true)
	public Optional<Account> findByIdOrEmpty(Long accountId) {
		return accountRepository.findById(accountId);
	}

	// 랭킹 목록(RankingService.getRankings)이 accountId 목록으로 Account+User를 N+1 없이 배치 조회할 때 쓴다.
	@Transactional(readOnly = true)
	public List<Account> getAccountsWithUser(List<Long> accountIds) {
		return accountRepository.findAllByIdInFetchUser(accountIds);
	}

	// 랭킹 재구성(RankingRebuildService)이 매도 이력 계좌 id 목록의 realized_pnl을 배치 조회할 때 쓴다(이슈 #279).
	// getAccountsWithUser와 달리 User를 fetch join하지 않는다 — 재구성은 닉네임을 쓰지 않고 (id, realizedPnl)만
	// 필요하다. AccountRepository에 신규 메서드를 만들지 않고 JpaRepository.findAllById를 그대로 위임한다.
	@Transactional(readOnly = true)
	public List<Account> getAccountsByIds(List<Long> accountIds) {
		return accountRepository.findAllById(accountIds);
	}

	@Transactional(readOnly = true)
	public AccountSummaryResponse getAccountSummary(Long userId, Market market) {
		Account account = getAccountFor(userId, market);

		List<HoldingValuationDto> valuations = holdingValuationService
			.evaluateActiveHoldingsForAccount(account.getId());
		long holdingsValue = 0L;
		long unrealizedPnl = 0L;
		for (HoldingValuationDto valuation : valuations) {
			if (valuation.priceStatus() == PriceStatus.AVAILABLE) {
				holdingsValue += valuation.evaluationAmount();
				unrealizedPnl += valuation.unrealizedPnl();
			} else {
				// 시세 무효(휴장 등)는 손익을 모르니 원가만큼 있는 것으로 취급 — 미실현손익은 0 기여
				holdingsValue += valuation.costBasis();
			}
		}

		long cashBalance = account.getCashBalance();
		long totalValue = cashBalance + holdingsValue - account.getSandboxCashAdjustment();
		long realizedPnl = account.getRealizedPnl();
		long seedMoney = account.getSeedMoney();
		BigDecimal returnRate = seedMoney == 0
			? BigDecimal.ZERO
			: BigDecimal.valueOf(totalValue - seedMoney)
				.divide(BigDecimal.valueOf(seedMoney), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		return AccountSummaryResponse.of(cashBalance, account.getReservedCash(), holdingsValue, totalValue,
			realizedPnl, unrealizedPnl, returnRate);
	}
}
