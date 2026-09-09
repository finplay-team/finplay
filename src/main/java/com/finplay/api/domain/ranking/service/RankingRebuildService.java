package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.store.RankingEntryDto;
import com.finplay.api.domain.ranking.store.RankingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RankingRebuildService {

	private final TradeService tradeService;
	private final AccountService accountService;
	private final RankingStore rankingStore;
	private final RankingRebuildLock rankingRebuildLock;

	@EventListener(ApplicationReadyEvent.class)
	public void rebuildOnStartup() {
		log.info("기동 시 랭킹 재구성 시작");
		rebuildAll();
	}

	@Scheduled(cron = "${ranking.rebuild.cron}", zone = "Asia/Seoul")
	public void rebuildOnSchedule() {
		log.info("정기 랭킹 재구성 시작");
		rebuildAll();
	}

	public void rebuildAll() {
		for (Market market : Market.values()) {
			try {
				rebuild(market);
			} catch (Exception e) {
				log.error("랭킹 재구성 실패. market={}", market, e);
			}
		}
	}

	public void rebuild(Market market) {
		Optional<String> lockToken = rankingRebuildLock.tryLock(market);
		if (lockToken.isEmpty()) {
			log.info(
				"랭킹 재구성 락을 얻지 못해 이번 실행을 건너뜁니다(다른 인스턴스가 처리 중이거나 Redis 문제로 락을 "
					+ "얻지 못함) - market={}",
				market);
			return;
		}
		try {
			long startedNanos = System.nanoTime();
			List<Long> accountIds = tradeService.getSoldAccountIds(market);
			List<RankingEntryDto> entries = new ArrayList<>(accountIds.size());
			for (int start = 0; start < accountIds.size(); start += RankingStore.REBUILD_CHUNK_SIZE) {
				int end = Math.min(start + RankingStore.REBUILD_CHUNK_SIZE, accountIds.size());
				for (Account account : accountService.getAccountsByIds(accountIds.subList(start, end))) {
					entries.add(new RankingEntryDto(account.getId(), account.getRealizedPnl()));
				}
			}
			if (rankingStore.replaceAll(market, entries)) {
				log.info("랭킹 재구성 완료. market={}, 대상 계좌 수={}, 소요={}ms", market, entries.size(),
					elapsedMillis(startedNanos));
			}
		} finally {
			rankingRebuildLock.unlock(market, lockToken.get());
		}
	}

	private long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}
