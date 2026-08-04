// 랭킹 점수 갱신과 시장별 랭킹 목록 조회(공동 순위 보정 포함)를 담당하는 서비스
package com.finplay.api.ranking.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.ranking.dto.RankingEntryDto;
import com.finplay.api.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.store.RankingStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RankingService {

	private static final int DEFAULT_LIMIT = 10;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 50;

	private final RankingStore rankingStore;
	private final AccountRepository accountRepository;

	// after-commit 이벤트 처리 시점에 DB에서 최신 realized_pnl을 다시 조회해 절댓값으로 ZADD한다.
	// 이벤트 도착 순서가 뒤바뀌어도 최종 수렴한다(spec.md 동시성 경합 Decision Gate).
	// 매도 이력이 없는 계좌는 이 메서드가 호출된 적이 없으므로 ZSET에 member 자체가 존재하지 않는다
	// — 별도의 "매도 이력 있음" 플래그 없이 쓰기 경로 설계로 랭킹 대상 제외가 해결된다(plan.md 7절).
	@Transactional
	public void refreshScore(Long accountId) {
		accountRepository.findById(accountId).ifPresentOrElse(
			account -> rankingStore.addScoreWithRetry(account.getMarket(), accountId, account.getRealizedPnl()),
			() -> log.warn("랭킹 갱신 대상 계좌를 찾을 수 없음. accountId={}", accountId));
	}

	public RankingListResponse getRankings(Market market, Integer limitParam) {
		int limit = clampLimit(limitParam);
		List<RankingEntryDto> window = rankingStore.topN(market, limit);
		if (window.isEmpty()) {
			return new RankingListResponse(market.name(), List.of());
		}

		Map<Long, Account> accountById = accountRepository
			.findAllByIdInFetchUser(window.stream().map(RankingEntryDto::accountId).toList())
			.stream()
			.collect(Collectors.toMap(Account::getId, account -> account));

		List<RankingListItemResponse> content = calculateRanks(window, market, accountById);
		return new RankingListResponse(market.name(), content);
	}

	// limitParam이 1 미만(0 이하)이거나 없으면 기본값 10, 50 초과면 50으로 클램핑한다.
	// 오류로 거부(controller 책임)가 아니라 요청값을 정책값으로 치환하는 비즈니스 규칙이라 서비스 책임이다(plan.md 192행).
	private int clampLimit(Integer limitParam) {
		if (limitParam == null || limitParam < MIN_LIMIT) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limitParam, MAX_LIMIT);
	}

	// 순위 보정 흐름(plan.md 6절): 1) score desc, 동점자는 userId asc로 재정렬
	// (Redis ZSET 자체 tie-break는 멤버 문자열의 사전순이라 신뢰할 수 없음)
	// 2) 고유 score마다 1회만 countStrictlyGreater를 호출해 캐시
	// 3) rank = countStrictlyGreater(score) + 1
	private List<RankingListItemResponse> calculateRanks(
		List<RankingEntryDto> window, Market market, Map<Long, Account> accountById) {

		List<RankingEntryDto> sorted = window.stream()
			.sorted(Comparator.comparingLong(RankingEntryDto::score)
				.reversed()
				.thenComparing(entry -> accountById.get(entry.accountId()).getUser().getId()))
			.toList();

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
