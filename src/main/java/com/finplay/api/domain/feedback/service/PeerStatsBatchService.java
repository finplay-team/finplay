package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import com.finplay.api.domain.portfolio.service.HolderPopulationQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PeerStatsBatchService {

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final HolderPopulationQueryService holderPopulationQueryService;

	private final Clock clock;

	@Scheduled(cron = "${feedback.batch.peer-stats-cron}", zone = "Asia/Seoul")
	public void runPeerStatsBatch() {
		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			log.info("재생세션이 준비되지 않아 집단 비교 배치를 건너뛴다.");
			return;
		}

		LocalDate originTradeDate = session.sourceTradingDate();
		LocalDate serviceDate = LocalDate.now(clock);
		List<PriceMoveEvent> cards = priceMoveEventRepository.findByMarketAndOriginTradeDate(Market.STOCK,
			originTradeDate);
		log.info("집단 비교 배치를 시작한다. 원본 거래일={} 서비스 날짜={} 카드={}건",
			originTradeDate, serviceDate, cards.size());

		long batchStartedNanos = System.nanoTime();
		int created = 0;
		for (PriceMoveEvent card : cards) {
			try {
				if (aggregateCard(card, serviceDate, LocalDateTime.of(serviceDate, card.getWindowEnd()))) {
					created++;
				}
			} catch (RuntimeException ex) {
				log.warn("집단 비교 집계에 실패해 이 카드를 건너뛴다. 카드={}", card.getId(), ex);
			}
		}
		log.info("집단 비교 배치를 마쳤다. 생성={}건 소요={}ms", created, elapsedMillis(batchStartedNanos));
	}

	@Scheduled(cron = "${feedback.batch.crypto-peer-stats-cron}", zone = "Asia/Seoul")
	public void runCryptoPeerStatsBatch() {
		LocalDate targetDate = LocalDate.now(clock).minusDays(1);
		LocalDateTime from = targetDate.atStartOfDay();
		LocalDateTime to = targetDate.plusDays(1).atStartOfDay().minusNanos(1_000L);
		List<PriceMoveEvent> cards = priceMoveEventRepository.findByMarketAndOccurredAtBetween(
			Market.CRYPTO, from, to);
		log.info("코인 집단 비교 배치를 시작한다. 대상 날짜={} 카드={}건", targetDate, cards.size());

		long batchStartedNanos = System.nanoTime();
		int created = 0;
		for (PriceMoveEvent card : cards) {
			try {
				LocalDateTime at = card.getOccurredAt();
				if (aggregateCard(card, at.toLocalDate(), at)) {
					created++;
				}
			} catch (RuntimeException ex) {
				log.warn("코인 집단 비교 집계에 실패해 이 카드를 건너뛴다. 카드={}", card.getId(), ex);
			}
		}
		log.info("코인 집단 비교 배치를 마쳤다. 생성={}건 소요={}ms", created, elapsedMillis(batchStartedNanos));
	}

	private boolean aggregateCard(PriceMoveEvent card, LocalDate serviceDate, LocalDateTime at) {
		if (priceMovePeerStatRepository.existsByPriceMoveEventIdAndServiceDate(card.getId(), serviceDate)) {
			log.debug("이미 집계된 카드라 건너뛴다. 카드={} 서비스 날짜={}", card.getId(), serviceDate);
			return false;
		}

		Long instrumentId = card.getInstrument().getId();

		HolderPopulationQueryService.PopulationSnapshot snapshot = holderPopulationQueryService
			.populationSnapshotAtTime(instrumentId, at);
		int holderCount = snapshot.holderCount();
		List<Integer> minutesToSell = snapshot.minutesToSell();

		int soldWithin30MinCount = (int)minutesToSell.stream().filter(minutes -> minutes <= 30).count();
		Integer medianMinutesToSell = median(minutesToSell);

		PriceMovePeerStat stat = PriceMovePeerStat.create(
			card, serviceDate, holderCount, soldWithin30MinCount, medianMinutesToSell, LocalDateTime.now(clock));
		priceMovePeerStatRepository.save(stat);
		return true;
	}

	private static Integer median(List<Integer> minutesToSell) {
		if (minutesToSell.isEmpty()) {
			return null;
		}
		List<Integer> sorted = new ArrayList<>(minutesToSell);
		Collections.sort(sorted);
		int size = sorted.size();
		int mid = size / 2;
		return size % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
	}

	private long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}
}
