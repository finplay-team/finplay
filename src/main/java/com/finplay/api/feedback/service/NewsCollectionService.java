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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 재생세션 확정·주식 시장 개장과 공유하는 상태가 없다. 그래서 여기에 방어적인 {@code catch}를 더 두지 않는다.
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
		LocalDateTime collectedAt = LocalDateTime.now(clock);
		int saved = 0;
		for (Market market : Market.values()) {
			List<Instrument> instruments = instrumentService.getInstrumentEntities(market);
			// 제목 필터는 같은 시장 안에서만 판정한다 — 시장을 섞으면 종목명이 겹치는 순간 정상 기사가 사라진다.
			List<String> sameMarketNames = instruments.stream().map(Instrument::getName).toList();
			for (Instrument instrument : instruments) {
				List<CollectedNewsDto> collected = newsCollector.collect(instrument, sameMarketNames);
				saved += save(instrument, MarketNewsItemType.NEWS, collected, collectedAt);
			}
		}
		log.info("뉴스 수집 완료 (신규 저장 {}건)", saved);
	}

	/**
	 * <b>주식 종목의</b> 공시를 수집한다. 코인에는 공시가 없다 (FEED-001·§C-3). 크론은
	 * {@code feedback.news.disclosure-cron}이다.
	 */
	@Scheduled(cron = "${feedback.news.disclosure-cron}", zone = "Asia/Seoul")
	public void collectDisclosures() {
		LocalDateTime collectedAt = LocalDateTime.now(clock);
		LocalDate collectionDate = LocalDate.now(clock);
		int saved = 0;
		for (Instrument instrument : instrumentService.getInstrumentEntities(Market.STOCK)) {
			List<CollectedNewsDto> collected = disclosureCollector.collect(instrument, collectionDate);
			saved += save(instrument, MarketNewsItemType.DISCLOSURE, collected, collectedAt);
		}
		log.info("공시 수집 완료 (신규 저장 {}건)", saved);
	}

	/**
	 * <b>중복은 오류가 아니라 무시한다</b>(FEED-001). 같은 기사가 30분마다 다시 조회되는 것이 정상 동작이라
	 * 이미 있으면 조용히 건너뛴다.
	 *
	 * @return 실제로 새로 저장한 건수
	 */
	private int save(
		Instrument instrument, MarketNewsItemType type, List<CollectedNewsDto> collected, LocalDateTime collectedAt) {
		int saved = 0;
		for (CollectedNewsDto item : collected) {
			if (marketNewsItemRepository.existsByInstrumentIdAndUrl(instrument.getId(), item.url())) {
				continue;
			}
			marketNewsItemRepository.save(MarketNewsItem.create(
				instrument, type, item.title(), item.publisher(), item.url(), item.publishedAt(), collectedAt));
			saved++;
		}
		return saved;
	}
}
