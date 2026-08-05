// 장 마감 뒤 집단 비교 확정 집계를 만드는 배치 오케스트레이션 — 카드별로 모집단·매도 시간을 계산해 저장한다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMovePeerStat;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import com.finplay.api.portfolio.service.HolderPopulationQueryService;
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

/**
 * 크론 값은 {@code application.yml}의 {@code feedback.batch.peer-stats-cron}이고 정본은 spec §C-1이다.
 * §C-6이 이름을 못박아 둔 신설 서비스다 — 카드별로 {@code HolderPopulationQueryService}의 모집단 조회를 불러
 * §반사실·집단 비교 계산의 세 지표(모집단 크기·30분 내 매도 개수·매도까지 걸린 시간의 중앙값)를 계산해
 * {@code price_move_peer_stats}에 저장한다.
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다.</b> {@code FeedbackBatchService}와 같은 이유다 — 모집단 재구성
 * 조회가 카드 수만큼 반복되므로, 배치 전체를 트랜잭션으로 감싸면 그 시간 내내 DB 커넥션을 쥐게 된다. 저장은
 * 카드 1건 단위로 {@code PriceMovePeerStatRepository#save}가 짧게 커밋한다({@code PriceMoveCardWriter} 같은
 * 별도 컴포넌트가 꼭 필요하지 않을 만큼 저장이 짧다).
 *
 * <p><b>중복 방지는 유니크 제약(§C-9)이 최종 방어선이지만, 이 배치는 저장 전에 존재를 먼저 확인한다.</b> 판정
 * 축은 {@code UNIQUE(price_move_event_id, service_date)}와 정확히 같다({@code priceMoveEventId}·
 * {@code serviceDate}) — 같은 서비스 날짜에 배치를 두 번 돌려도 예외 없이 스킵된다.
 *
 * <p><b>{@code serviceDate}는 배치 실행 시점의 오늘 날짜다({@code Clock} 기반).</b> 같은 원본 거래일이 두 번
 * 재생돼도 카드 행은 재사용되므로(§C-9), 이 값이 재재생마다 달라야 두 서비스 날짜의 집계가 따로 쌓인다 —
 * 덮어쓰면 첫날 매도자의 통계가 사라진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeerStatsBatchService {

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final HolderPopulationQueryService holderPopulationQueryService;

	private final Clock clock;

	/**
	 * 장 마감 집단 비교 배치 진입점.
	 *
	 * <p><b>재생세션이 {@code READY}가 아니면 아무것도 하지 않는다</b>(다른 배치와 같은 패턴, FEED-004). 원본
	 * 거래일이 확정되지 않으면 그날 카드 자체를 조회할 수 없다.
	 *
	 * <p><b>{@code zone}을 반드시 붙인다</b>(§C-1). 배포 JVM 기본 타임존이 UTC라 빠뜨리면 이 배치가 KST 15:32가
	 * 아니라 다른 시각에 돌아, 조회 시점에 {@code peerComparison.status}가 계속 {@code NOT_YET}으로 남는다.
	 */
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

		// 소요 시간은 System.nanoTime()으로 잰다 — Clock으로 재면 통합 테스트가 항상 0으로 통과한다(이슈 #198).
		long batchStartedNanos = System.nanoTime();
		int created = 0;
		for (PriceMoveEvent card : cards) {
			// 카드 하나가 실패해도 다음으로 넘어간다 — 배치 전체를 실패시키지 않는다(다른 배치와 같은 패턴).
			try {
				if (aggregateCard(card, serviceDate)) {
					created++;
				}
			} catch (RuntimeException ex) {
				log.warn("집단 비교 집계에 실패해 이 카드를 건너뛴다. 카드={}", card.getId(), ex);
			}
		}
		log.info("집단 비교 배치를 마쳤다. 생성={}건 소요={}ms", created, elapsedMillis(batchStartedNanos));
	}

	/**
	 * 카드 1건의 집단 비교를 확정한다. 이미 그 서비스 날짜의 집계가 있으면 다시 계산하지 않는다.
	 *
	 * @return 새로 저장했으면 {@code true}, 이미 있어 건너뛰었으면 {@code false}
	 */
	private boolean aggregateCard(PriceMoveEvent card, LocalDate serviceDate) {
		if (priceMovePeerStatRepository.existsByPriceMoveEventIdAndServiceDate(card.getId(), serviceDate)) {
			log.debug("이미 집계된 카드라 건너뛴다. 카드={} 서비스 날짜={}", card.getId(), serviceDate);
			return false;
		}

		// T = 카드 windowEnd를 그 카드가 재생된 서비스 날짜에 붙인 절대 시각 (§반사실·집단 비교 계산).
		LocalDateTime at = LocalDateTime.of(serviceDate, card.getWindowEnd());
		Long instrumentId = card.getInstrument().getId();

		int holderCount = holderPopulationQueryService.countHoldersAtTime(instrumentId, at);
		List<Integer> minutesToSell = holderPopulationQueryService.minutesToSellForHoldersAtTime(instrumentId, at);

		int soldWithin30MinCount = (int)minutesToSell.stream().filter(minutes -> minutes <= 30).count();
		Integer medianMinutesToSell = median(minutesToSell);

		PriceMovePeerStat stat = PriceMovePeerStat.create(
			card, serviceDate, holderCount, soldWithin30MinCount, medianMinutesToSell, LocalDateTime.now(clock));
		priceMovePeerStatRepository.save(stat);
		return true;
	}

	// 장 마감까지 안 판 회원은 이미 minutesToSell 목록에서 빠져 있다(HolderPopulationQueryService 계약) —
	// 보유자 전원이 미매도면 목록이 비어 null을 반환한다(§C-8) — "보유자 전원이 미매도"의 의미다.
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
