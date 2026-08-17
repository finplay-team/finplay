// 뉴스·공시를 종일 상시 수집해 market_news_items에 저장하는 진입점 — 수집기 호출과 저장을 담당한다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.collector.CollectedNewsDto;
import com.finplay.api.feedback.collector.DisclosureCollector;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 크론 2종의 값은 {@code application.yml}의 {@code feedback.news.*}이고 정본은 spec §C-1이다.
 *
 * <p><b>수집은 재생 시점이 아니라 기사가 나오는 당일에, 종일 돈다</b>(FEED-001). 장중으로 한정하면 안 된다 —
 * {@code 전장} 구간(§C-2)이 약 17.5시간인데 장중만 돌리면 그 대부분이 <b>영영 수집되지 않고</b>, 저녁·야간
 * 기사가 다음 날 아침 브리핑의 주재료다. 그래서 뉴스 크론에 시간대 제한이 없다.
 *
 * <p><b>발행일자로 거르지 않는다</b>(FEED-001). 수집기가 준 것을 {@code published_at} 그대로 저장하고 구간
 * 필터는 조회·매칭 시점에만 건다. 수집 시점에 "원본 거래일 것만" 남기면 D-1 저녁 기사와 D 새벽 기사 중 한쪽이
 * 반드시 버려진다.
 *
 * <p><b>{@code createdAt}은 발행 시각이 아니라 수집 시각이다</b>(§데이터 모델). 요약 재생성 판정이 이 값을 직전
 * {@code generated_at}과 비교한다(FEED-008).
 *
 * <p><b>원장에 쓰지 않는다.</b> 이 클래스가 주입받는 리포지토리는 {@code MarketNewsItemRepository} 하나뿐이고
 * {@code instruments}는 {@code InstrumentService}를 통해 <b>읽기만</b> 한다 — 주문·체결·계좌·잔액·보유·손익
 * 테이블에 닿는 경로가 애초에 없다. 종목 목록을 리포지토리가 아니라 서비스로 얻는 것은 §C-6의 규칙이다.
 *
 * <p><b>이 메서드들은 트랜잭션을 열지 않는다.</b> 종목별 외부 HTTP 호출이 28종 순차로 일어나는 동안 DB 커넥션을
 * 점유하지 않기 위해서다({@code KisHistoricalCandleCollector}가 같은 이유로 같은 형태다). 저장은 리포지토리
 * 호출 단위로 각각 커밋되므로, 한 기사가 실패해도 앞서 저장된 기사는 남는다.
 *
 * <p><b>수집 실패가 다른 경로를 막지 않는다</b>(FEED-001·§실패 처리). 두 수집기 모두 실패를 예외가 아니라 빈
 * 목록으로 돌려주는 것이 계약이고, 이 배치는 자기 스레드에서 독립적으로 돌며 원장에 쓰지 않는다 — 분봉 수집·
 * 재생세션 확정·주식 시장 개장과 공유하는 상태가 없다.
 *
 * <p><b>2026-08-17 정정 (이슈 #408).</b> 그전까지 위 문단은 "그래서 여기에 방어적인 {@code catch}를 더 두지
 * 않는다"로 끝났다. 근거가 어긋나 있었다 — <b>던지는 것은 수집기가 아니라 {@code save}다.</b> 조회-후-삽입
 * 구조이고 트랜잭션이 없어 건별 커밋되므로, 경합이 나면
 * {@code uk_market_news_items_instrument_url}에서 {@code DataIntegrityViolationException}이 그 자리에서
 * 올라온다. {@code Market.values()}가 {@code STOCK → CRYPTO} 순이라 <b>주식 종목 하나의 실패가 남은 주식과
 * 코인 전 종목 수집을 통째로 건너뛰었고</b>, {@code @Scheduled}가 삼켜 다음 회차까지 드러나지 않았다.
 * 지금은 종목 루프가 {@code catch}로 격리되고, 저장은 <b>중복 행만</b> 삼킨다(아래 {@code save} 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCollectionService {

	private final NewsCollector newsCollector;

	private final DisclosureCollector disclosureCollector;

	private final InstrumentService instrumentService;

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final Clock clock;

	/**
	 * 주식·코인 <b>전 종목</b>의 뉴스를 수집한다 (FEED-001). 크론은 {@code feedback.news.collect-cron}이다.
	 *
	 * <p>{@code zone}을 반드시 붙인다(§C-1). 빠뜨리면 배포 JVM 기본 타임존이 UTC라 <b>예외도 로그도 없이</b>
	 * 엉뚱한 시각에 돈다.
	 */
	@Scheduled(cron = "${feedback.news.collect-cron}", zone = "Asia/Seoul")
	public void collectNews() {
		int saved = 0;
		int failed = 0;
		for (Market market : Market.values()) {
			List<Instrument> instruments = instrumentService.getInstrumentEntities(market);
			// 제목 필터는 같은 시장 안에서만 판정한다 — 시장을 섞으면 종목명이 겹치는 순간 정상 기사가 사라진다.
			List<String> sameMarketNames = instruments.stream().map(Instrument::getName).toList();
			for (Instrument instrument : instruments) {
				// 종목 하나가 실패해도 나머지는 계속한다 (이슈 #408). CryptoPriceMoveWatcher.watch()·
				// FeedbackBatchService.generateNewsSummaries가 이미 쓰는 형태다.
				try {
					List<CollectedNewsDto> collected = newsCollector.collect(instrument, sameMarketNames);
					saved += save(instrument, MarketNewsItemType.NEWS, collected);
				} catch (RuntimeException ex) {
					failed++;
					log.warn("뉴스 수집 중 종목 하나가 실패해 건너뛴다. 종목={}", instrument.getId(), ex);
				}
			}
		}
		log.info("뉴스 수집 완료 (신규 저장 {}건, 실패 종목 {}건)", saved, failed);
	}

	/**
	 * 종목 하나만 온디맨드로 수집한다 (ADR-0017 §결정 2). 이 메서드의 유일한 소비자는
	 * {@code CryptoPriceMoveWatcher}다. 코인 온디맨드 수집 전용이며, 배치 진입점({@code collectNews})과
	 * 별개로 언제든 호출될 수 있다.
	 *
	 * <p>{@code sameMarketNames}는 {@code collectNews()}와 같은 규칙(같은 시장 안에서만 제목 필터)이지만
	 * 매번 다시 조회한다 — 온디맨드 호출은 드물어(게이트를 다 통과한 뒤, 첫 매칭이 빈 경우에만) 비용이 무시할
	 * 만하다.
	 *
	 * <p>타입은 {@link MarketNewsItemType#NEWS}로 고정한다. 공시는 코인에 없다(§C-3).
	 *
	 * @return 실제로 새로 저장한 건수
	 */
	public int collectForInstrument(Instrument instrument) {
		List<Instrument> sameMarket = instrumentService.getInstrumentEntities(instrument.getMarket());
		List<String> sameMarketNames = sameMarket.stream().map(Instrument::getName).toList();
		List<CollectedNewsDto> collected = newsCollector.collect(instrument, sameMarketNames);
		return save(instrument, MarketNewsItemType.NEWS, collected);
	}

	/**
	 * <b>주식 종목의</b> 공시를 수집한다. 코인에는 공시가 없다 (FEED-001·§C-3). 크론은
	 * {@code feedback.news.disclosure-cron}이다.
	 */
	@Scheduled(cron = "${feedback.news.disclosure-cron}", zone = "Asia/Seoul")
	public void collectDisclosures() {
		LocalDate collectionDate = LocalDate.now(clock);
		int saved = 0;
		int failed = 0;
		for (Instrument instrument : instrumentService.getInstrumentEntities(Market.STOCK)) {
			// 뉴스 쪽과 같은 이유로 종목 단위로 격리한다 (이슈 #408).
			try {
				List<CollectedNewsDto> collected = disclosureCollector.collect(instrument, collectionDate);
				saved += save(instrument, MarketNewsItemType.DISCLOSURE, collected);
			} catch (RuntimeException ex) {
				failed++;
				log.warn("공시 수집 중 종목 하나가 실패해 건너뛴다. 종목={}", instrument.getId(), ex);
			}
		}
		log.info("공시 수집 완료 (신규 저장 {}건, 실패 종목 {}건)", saved, failed);
	}

	/**
	 * <b>중복은 오류가 아니라 무시한다</b>(FEED-001). 같은 기사가 30분마다 다시 조회되는 것이 정상 동작이라
	 * 이미 있으면 조용히 건너뛴다.
	 *
	 * <p>이미 저장된 URL을 <b>종목당 한 번에</b> 조회해 메모리에서 대조한다. 그 집합에 이번에 저장한 URL도 함께
	 * 넣으므로 <b>같은 응답 안에 같은 기사가 두 번 들어 있어도</b> 두 번째가 걸린다 — 건별로 묻던 방식은 앞선
	 * {@code save}가 이미 커밋돼 있어 그 경우가 저절로 막혔지만, 한 번에 묻는 방식에서는 여기서 막아야 한다.
	 *
	 * @return 실제로 새로 저장한 건수
	 */
	private int save(Instrument instrument, MarketNewsItemType type, List<CollectedNewsDto> collected) {
		if (collected.isEmpty()) {
			return 0;
		}
		Set<String> seen = new HashSet<>(marketNewsItemRepository.findExistingUrls(
			instrument.getId(), collected.stream().map(CollectedNewsDto::url).toList()));
		int saved = 0;
		for (CollectedNewsDto item : collected) {
			if (!seen.add(item.url())) {
				continue;
			}
			try {
				marketNewsItemRepository.save(MarketNewsItem.create(
					instrument, type, item.title(), item.publisher(), item.url(), item.publishedAt(),
					// 저장 직전에 찍는다 (이슈 #408). 배치 시작 시각을 쓰면 회차가 길어질 때(34종목 × 읽기
					// 타임아웃 10초) 그 사이에 돈 요약 배치의 generated_at보다 이른 created_at으로 저장돼,
					// existsByInstrumentIdAndCreatedAtAfter가 그 기사를 잡지 못한다 —
					// MarketNewsItemRepository가 published_at으로 비교하면 안 된다고 경고한 것과 같은 함정을
					// 저장 시각 쪽에서 재현하는 셈이다.
					LocalDateTime.now(clock)));
				saved++;
			} catch (DataIntegrityViolationException ex) {
				absorbOnlyDuplicateRow(instrument, item, ex);
			}
		}
		return saved;
	}

	/**
	 * <b>중복 행만</b> 삼키고 그 밖의 무결성 위반은 {@code WARN}으로 남긴다.
	 *
	 * <p>삼켜도 되는 경우는 하나다 — 위 {@code findExistingUrls}와 {@code save} 사이에 다른 실행이 같은 기사를
	 * 먼저 넣는 경우다. 코인 온디맨드 수집({@code CryptoPriceMoveWatcher})이 매 분 30초 스케줄 스레드에서 같은
	 * 테이블에 쓰므로 이 창은 실재한다. 중복은 이미 FEED-001의 계약이라 조용히 넘긴다.
	 *
	 * <p><b>판정을 예외 타입이 아니라 "행이 실제로 있는가"로 한다.</b> 근거는
	 * {@code PostSellFeedbackService.absorbOnlyDuplicateRow}와 같다 — Hibernate의
	 * {@code ConstraintViolationException}이 기반 타입으로 번역되는지 {@code DuplicateKeyException}까지 붙는지는
	 * 환경에 달려 있어, 타입으로 좁히면 정상 경합이 그대로 올라온다.
	 *
	 * <p>행 재조회는 원래 막으려던 상태를 정확히 가른다. NOT NULL 위반이나 utf8mb4가 거부하는 문자열(제목 절단이
	 * 서로게이트 쌍을 가르는 경우)은 행이 안 생기므로 {@code WARN}으로 드러난다 — 조용히 넘기면 그 기사는 매
	 * 회차 다시 시도되면서 아무 신호도 남지 않는다.
	 */
	private void absorbOnlyDuplicateRow(
		Instrument instrument, CollectedNewsDto item, DataIntegrityViolationException ex) {
		if (!marketNewsItemRepository.findExistingUrls(instrument.getId(), List.of(item.url())).isEmpty()) {
			log.debug("같은 기사가 이미 저장돼 있어 이번 저장은 건너뛴다. 종목={} url={}",
				instrument.getId(), item.url());
			return;
		}
		log.warn("기사 저장이 무결성 위반으로 실패했고 행도 없다. 종목={} url={}", instrument.getId(), item.url(), ex);
	}
}
