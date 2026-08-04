// 랭킹 점수 갱신과 시장별 랭킹 목록 조회(공동 순위 보정 포함)를 담당하는 서비스
package com.finplay.api.ranking.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.ranking.dto.RankingEntryDto;
import com.finplay.api.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.store.RankingStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RankingService {

	private static final int DEFAULT_LIMIT = 10;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 50;

	private final RankingStore rankingStore;
	private final AccountService accountService;

	// after-commit 이벤트 처리 시점에 DB에서 최신 realized_pnl을 다시 조회해 절댓값으로 ZADD한다.
	// 이벤트 도착 순서가 뒤바뀌어도 최종 수렴한다(spec.md 동시성 경합 Decision Gate).
	// 매도 이력이 없는 계좌는 이 메서드가 호출된 적이 없으므로 ZSET에 member 자체가 존재하지 않는다
	// — 별도의 "매도 이력 있음" 플래그 없이 쓰기 경로 설계로 랭킹 대상 제외가 해결된다(plan.md 7절).
	// REQUIRES_NEW(+readOnly)로 새 트랜잭션·새 영속성 컨텍스트를 강제한다 — @TransactionalEventListener(AFTER_COMMIT)
	// 콜백은 원래 매도 트랜잭션의 EntityManager가 아직 스레드에 바인딩된 시점에 실행되므로, 기본 전파(REQUIRED)를
	// 쓰면 그 1차 캐시에 있는 계좌 객체를 그대로 반환해 DB 재조회가 실제로 일어나지 않는다(PR #196 리뷰 지적).
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void refreshScore(Long accountId) {
		accountService.findByIdOrEmpty(accountId).ifPresentOrElse(
			account -> rankingStore.addScoreWithRetry(account.getMarket(), accountId, account.getRealizedPnl()),
			() -> log.warn("랭킹 갱신 대상 계좌를 찾을 수 없음. accountId={}", accountId));
	}

	public RankingListResponse getRankings(Market market, Integer limitParam) {
		int limit = clampLimit(limitParam);
		List<RankingEntryDto> window = fetchWindowResolvingBoundaryTies(market, limit);
		if (window.isEmpty()) {
			return new RankingListResponse(market.name(), List.of());
		}

		Map<Long, Account> accountById = accountService
			.getAccountsWithUser(window.stream().map(RankingEntryDto::accountId).toList())
			.stream()
			.collect(Collectors.toMap(Account::getId, account -> account));

		List<RankingListItemResponse> content = calculateRanks(window, market, accountById, limit);
		return new RankingListResponse(market.name(), content);
	}

	// PR #196 리뷰 지적(차단 2): topN(limit)만 가져오면 "어떤 동점자가 window에 들어갈지"가 Redis 멤버 문자열의
	// 사전순으로 결정돼 plan.md 정책(동점자는 userId 오름차순)과 다르게 잘릴 수 있다. limit+1개를 가져와
	// limit번째·limit+1번째 항목의 score가 같은지 확인해 경계에 동점 그룹이 걸쳐 있는지 판단한다.
	private List<RankingEntryDto> fetchWindowResolvingBoundaryTies(Market market, int limit) {
		List<RankingEntryDto> window = rankingStore.topN(market, limit + 1);
		if (window.size() <= limit) {
			// 전체 멤버 수가 limit 이하 — 경계 자체가 없다.
			return window;
		}

		long boundaryScore = window.get(limit - 1).score();
		long justPastBoundaryScore = window.get(limit).score();
		if (boundaryScore != justPastBoundaryScore) {
			// 경계에 동점이 없는(가장 흔한) 경우 — 추가 Redis 호출 없이 그대로 반환한다.
			// 여기서 limit개로 미리 자르지 않는다: window에 유령 accountId(PR #196 차단 1)가 섞여 있으면
			// calculateRanks의 필터링으로 유효 항목이 limit보다 줄어드는데, 미리 잘라두면 "+1"로 확보해둔
			// 여유분까지 함께 잘려나가 보충할 데이터가 없어진다(PR #196 리뷰 후속 발견). 최종 limit개 절단은
			// calculateRanks가 유령 필터링 이후에 수행하므로 그쪽에 맡긴다.
			return window;
		}

		// 경계에 동점 그룹이 걸쳐 있다 — 그 score를 가진 전체 멤버를 가져와 window의 경계 score 항목을 교체한다.
		// 최종 정렬·절단(score desc, userId asc → limit개)은 calculateRanks가 담당한다.
		List<RankingEntryDto> allAtBoundaryScore = rankingStore.findAllAtScore(market, boundaryScore);
		Map<Long, RankingEntryDto> merged = new LinkedHashMap<>();
		for (RankingEntryDto entry : window) {
			if (entry.score() != boundaryScore) {
				merged.put(entry.accountId(), entry);
			}
		}
		for (RankingEntryDto entry : allAtBoundaryScore) {
			merged.put(entry.accountId(), entry);
		}
		return List.copyOf(merged.values());
	}

	// limitParam이 1 미만(0 이하)이거나 없으면 기본값 10, 50 초과면 50으로 클램핑한다.
	// 오류로 거부(controller 책임)가 아니라 요청값을 정책값으로 치환하는 비즈니스 규칙이라 서비스 책임이다(plan.md 192행).
	private int clampLimit(Integer limitParam) {
		if (limitParam == null || limitParam < MIN_LIMIT) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limitParam, MAX_LIMIT);
	}

	// 순위 보정 흐름(plan.md 6절): 0) Redis window에 DB 계좌가 없는 항목(PR #196 리뷰 지적, 차단 1)을 걸러낸다
	// — Redis는 MySQL 트랜잭션 밖의 파생 데이터라 DB 리셋·복원 등으로 언제든 어긋날 수 있으므로 정상 상황으로
	// 취급하고 로그만 남긴다. 1) score desc, 동점자는 userId asc로 재정렬
	// (Redis ZSET 자체 tie-break는 멤버 문자열의 사전순이라 신뢰할 수 없음)
	// 2) 정렬 후 정확히 limit개로 절단(limit+1 이상을 가져온 경계 동점 병합 결과를 여기서 최종 절단)
	// 3) 고유 score마다 1회만 countStrictlyGreater를 호출해 캐시, rank = countStrictlyGreater(score) + 1
	private List<RankingListItemResponse> calculateRanks(
		List<RankingEntryDto> window, Market market, Map<Long, Account> accountById, int limit) {

		List<RankingEntryDto> validEntries = window.stream()
			.filter(entry -> {
				boolean present = accountById.containsKey(entry.accountId());
				if (!present) {
					log.warn(
						"랭킹 window에 DB 계좌가 없는 accountId가 있어 제외함. accountId={}, market={}",
						entry.accountId(), market);
				}
				return present;
			})
			.toList();

		List<RankingEntryDto> sorted = validEntries.stream()
			.sorted(Comparator.comparingLong(RankingEntryDto::score)
				.reversed()
				.thenComparing(entry -> accountById.get(entry.accountId()).getUser().getId()))
			.limit(limit)
			.toList();

		// 주의: countStrictlyGreater는 Redis ZSET 자체를 ZCOUNT하므로, 위에서 걸러낸 유령 accountId가 여전히
		// ZSET에 남아 있다면 그 score를 여전히 카운트에 포함시킬 수 있다(경계값이어야만 순위에 영향).
		// NPE로 500이 나는 차단 사항 해결이 이번 수정의 핵심이라, 이 미세한 부정확성까지는 처리하지 않는다
		// (PR #196 리뷰가 명시적으로 과설계를 경계한 부분).
		Map<Long, Long> rankByScore = new HashMap<>();
		List<RankingListItemResponse> content = new ArrayList<>();
		for (RankingEntryDto entry : sorted) {
			long rank = rankByScore.computeIfAbsent(entry.score(),
				score -> rankingStore.countStrictlyGreater(market, score) + 1);
			Account account = accountById.get(entry.accountId());
			content.add(new RankingListItemResponse((int)rank, account.getUser().getNickname(), entry.score()));
		}
		return content;
	}
}
