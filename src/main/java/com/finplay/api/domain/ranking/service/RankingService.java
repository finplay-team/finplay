package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.entity.RankingStatus;
import com.finplay.api.domain.ranking.store.RankingEntryDto;
import com.finplay.api.domain.ranking.store.RankingStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
	private final TradeService tradeService;

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void refreshScore(Long accountId) {
		accountService.findByIdOrEmpty(accountId).ifPresentOrElse(
			account -> rankingStore.addScoreWithRetry(account.getMarket(), accountId, account.getRealizedPnl()),
			() -> log.warn("랭킹 갱신 대상 계좌를 찾을 수 없음. accountId={}", accountId));
	}

	public RankingListResponse getRankings(Market market, Integer limitParam) {
		int limit = clampLimit(limitParam);
		try {
			return getRankingsOrThrow(market, limit);
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.RANKING_STORE_UNAVAILABLE) {
				throw e;
			}
			return new RankingListResponse(market.name(), RankingStatus.UNAVAILABLE, List.of());
		}
	}

	private RankingListResponse getRankingsOrThrow(Market market, int limit) {
		List<RankingEntryDto> window = fetchWindowResolvingBoundaryTies(market, limit);
		if (window.isEmpty()) {
			RankingStatus status = tradeService.hasAnySellHistory(market)
				? RankingStatus.REBUILDING
				: RankingStatus.READY;
			return new RankingListResponse(market.name(), status, List.of());
		}

		Map<Long, Account> accountById = accountService
			.getAccountsWithUser(window.stream().map(RankingEntryDto::accountId).toList())
			.stream()
			.collect(Collectors.toMap(Account::getId, account -> account));

		List<RankingListItemResponse> content = calculateRanks(window, market, accountById, limit);
		return new RankingListResponse(market.name(), RankingStatus.READY, content);
	}

	public MyRankingResponse getMyRanking(Long userId, Market market) {
		Account account = accountService.getAccountForWithUser(userId, market);
		try {
			return getMyRankingOrThrow(market, account);
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.RANKING_STORE_UNAVAILABLE) {
				throw e;
			}
			return new MyRankingResponse(market.name(), RankingStatus.UNAVAILABLE, null,
				account.getUser().getNickname(), 0L);
		}
	}

	private MyRankingResponse getMyRankingOrThrow(Market market, Account account) {
		Long score = rankingStore.score(market, account.getId());
		Integer rank = score == null
			? null
			: (int)(rankingStore.countStrictlyGreater(market, score) + 1);
		long realizedPnl = score == null ? 0L : score;
		RankingStatus status = score == null && tradeService.hasSellHistory(account.getId())
			? RankingStatus.REBUILDING
			: RankingStatus.READY;
		return new MyRankingResponse(market.name(), status, rank, account.getUser().getNickname(), realizedPnl);
	}

	private List<RankingEntryDto> fetchWindowResolvingBoundaryTies(Market market, int limit) {
		List<RankingEntryDto> window = rankingStore.topN(market, limit + 1);
		if (window.size() <= limit) {
			return window;
		}

		long boundaryScore = window.get(limit - 1).score();
		long justPastBoundaryScore = window.get(limit).score();
		if (boundaryScore != justPastBoundaryScore) {
			return window;
		}

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

	private int clampLimit(Integer limitParam) {
		if (limitParam == null || limitParam < MIN_LIMIT) {
			return DEFAULT_LIMIT;
		}
		return Math.min(limitParam, MAX_LIMIT);
	}

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
