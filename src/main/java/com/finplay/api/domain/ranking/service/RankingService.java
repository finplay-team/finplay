// 랭킹 점수 갱신과 시장별 랭킹 목록 조회(공동 순위 보정 포함)를 담당하는 서비스
package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.entity.RankingStatus;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
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
	// status 판정(이슈 #279)에만 쓰는 의존이다. order 도메인이 ranking 이벤트를 발행하는 방향
	// (OrderExecutionService → RealizedPnlUpdatedEvent)과 반대라 순환 참조로 보일 수 있으나 타입 수준 순환이
	// 아니다 — OrderExecutionService가 참조하는 것은 ApplicationEventPublisher와 account 도메인의 이벤트
	// record뿐이고 ranking 패키지의 어떤 빈도 주입하지 않는다. 스프링 빈 그래프상으로도 RankingService →
	// TradeService의 단방향이다(plan.md "레이어 배치"). TradeRepository를 직접 주입하지 않는 것도 같은 기준이다(ADR-0002).
	private final TradeService tradeService;

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
		try {
			return getRankingsOrThrow(market, limit);
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.RANKING_STORE_UNAVAILABLE) {
				throw e;
			}
			// Redis 연결 장애 — 유실(REBUILDING)과 달리 재구성으로 해결되지 않는다. 500 대신 200 +
			// UNAVAILABLE로 응답한다(이슈 #288). 로그는 RankingStore.unavailable에서 이미 남겼다.
			return new RankingListResponse(market.name(), RankingStatus.UNAVAILABLE, List.of());
		}
	}

	private RankingListResponse getRankingsOrThrow(Market market, int limit) {
		List<RankingEntryDto> window = fetchWindowResolvingBoundaryTies(market, limit);
		if (window.isEmpty()) {
			// window가 비었다 == ZSET에 멤버가 하나도 없다. topN은 limit+1(최소 2)개를 요청하므로 멤버가 하나라도
			// 있으면 window는 비지 않는다 — 별도 ZCARD 왕복 없이 이 값으로 카디널리티 0을 판정할 수 있다.
			// DB 조회(hasAnySellHistory)는 이 분기에서만 일어난다. 랭킹에 사람이 한 명이라도 있으면 status
			// 판정을 위한 추가 왕복이 전혀 없다(정상 경로 비용 0, 이슈 #279).
			RankingStatus status = tradeService.hasAnySellHistory(market)
				? RankingStatus.REBUILDING
				: RankingStatus.READY;
			return new RankingListResponse(market.name(), status, List.of());
		}

		Map<Long, Account> accountById = accountService
			.getAccountsWithUser(window.stream().map(RankingEntryDto::accountId).toList())
			.stream()
			.collect(Collectors.toMap(Account::getId, account -> account));

		// window가 비지 않은 경로는 항상 READY다. calculateRanks의 DB 부재 필터링(8-1절)으로 content가 0건이
		// 될 수 있는데, 그건 ZSET 유실이 아니라 Redis/DB 불일치라는 다른 상황이라 REBUILDING으로 표시하면
		// 상태값의 의미가 흐려진다 — READY + 빈 content로 둔다(plan.md "상태(status) 판정").
		// 목록은 부분 유실(멤버 일부 누락)을 감지하지 않는다. 매 조회마다 전체 집계 비교가 필요하고, 대부분이
		// 정확한 목록에 "준비 중" 경고를 띄우는 것이 과한 신호이기 때문이다(spec.md 비즈니스 규칙).
		List<RankingListItemResponse> content = calculateRanks(window, market, accountById, limit);
		return new RankingListResponse(market.name(), RankingStatus.READY, content);
	}

	// 인증 사용자 본인의 시장별 순위를 단건으로 계산한다(RANK-002). RANK-001의 topN 목록과 달리 상위 limit건
	// 안에 들지 않아도 항상 정확한 보정 순위를 반환한다. 순위 산정의 score는 항상 Redis ZSET(RankingStore.score)
	// 기준이다 — DB accounts.realized_pnl을 직접 재사용하지 않는다. RANK-001 목록 조회와 동일한 ZSET 상태를
	// 기준으로 계산해야 두 엔드포인트가 서로 다른 순위를 보여주는 불일치가 생기지 않기 때문이다(plan.md
	// "RANK-002 설계" 참고). realizedPnl도 이 score를 그대로 노출한다(PR #234 리뷰 차단) — rank를 계산한 값과
	// 다른 값(DB accounts.realized_pnl)을 응답에 함께 실으면, after-commit 반영 지연·재시도 소진 등으로 두 값이
	// 어긋난 계좌에서 "이 손익, 이 순위"가 서로 대응하지 않는 응답이 나간다. score가 null이면 DB 값과 무관하게
	// 0을 싣는다 — status가 READY면(매도 이력 자체가 없음) DB realized_pnl도 0이라 값이 갈리지 않지만,
	// REBUILDING이면(매도 이력은 있는데 ZSET에서 유실됨) DB realized_pnl은 0이 아닐 수 있다. 그래도 DB 값을
	// 대신 싣지 않는다: rank가 null인 응답에 손익만 실제 값을 채우면 "이 손익, 이 순위"의 대응이 다시 깨진다.
	// REBUILDING일 때의 이 0은 "손익이 0"이 아니라 "아직 신뢰할 수 없음"을 뜻하며, 그 구별이 status의 존재
	// 이유다(이슈 #279).
	// 형제 메서드 getRankings와 동일한 패턴(PR #234 리뷰 권장 반영): 이 메서드 자체는 트랜잭션으로 감싸지 않는다
	// — accountService.getAccountForWithUser가 User를 fetch join으로 미리 로딩해 자신의 트랜잭션 안에서 끝내므로,
	// 이후 account.getUser().getNickname() 접근과 Redis 왕복 2회가 전부 트랜잭션 밖에서 일어나 DB 커넥션을
	// 점유하지 않는다.
	public MyRankingResponse getMyRanking(Long userId, Market market) {
		// 계좌·닉네임은 DB 조회라 Redis 장애와 무관하다 — UNAVAILABLE 응답에도 그대로 실을 수 있다.
		Account account = accountService.getAccountForWithUser(userId, market);
		try {
			return getMyRankingOrThrow(market, account);
		} catch (BusinessException e) {
			if (e.getErrorCode() != ErrorCode.RANKING_STORE_UNAVAILABLE) {
				throw e;
			}
			// Redis 연결 장애(이슈 #288). rank는 REBUILDING과 같은 모양(null)으로 두고, realizedPnl도 같은
			// 원칙(score가 없을 때의 0)을 그대로 따른다 — 신뢰할 수 없는 값을 채우지 않는다.
			return new MyRankingResponse(market.name(), RankingStatus.UNAVAILABLE, null,
				account.getUser().getNickname(), 0L);
		}
	}

	private MyRankingResponse getMyRankingOrThrow(Market market, Account account) {
		Long score = rankingStore.score(market, account.getId());
		Integer rank = score == null
			? null
			// RANK-001과 동일한 공동 순위 보정 공식(countStrictlyGreater + 1)을 재사용한다 — 별도의 새 보정
			// 공식을 만들지 않는다.
			: (int)(rankingStore.countStrictlyGreater(market, score) + 1);
		long realizedPnl = score == null ? 0L : score;
		// 내 랭킹은 목록과 달리 부분 유실까지 잡는다(의도적 비대칭, 이슈 #279). 내 것 1건만 확인하는 인덱스
		// 조회라 싸고, 틀릴 때 당사자는 "매도했는데 매도 이력이 없다"는 잘못된 안내를 100% 받게 된다.
		// DB 조회는 score == null일 때만 일어난다(&& 단축 평가) — 랭킹에 들어 있는 사용자는 추가 왕복이 없다.
		// score != null이면 항상 READY다. 내 점수가 있는데 다른 사람 점수가 유실됐는지까지는 판정하지 않는다
		// (그 판정은 전체 비교와 같은 비용이다).
		RankingStatus status = score == null && tradeService.hasSellHistory(account.getId())
			? RankingStatus.REBUILDING
			: RankingStatus.READY;
		return new MyRankingResponse(market.name(), status, rank, account.getUser().getNickname(), realizedPnl);
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
