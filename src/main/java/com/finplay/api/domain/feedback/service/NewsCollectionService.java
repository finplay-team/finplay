package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.collector.CollectedNewsDto;
import com.finplay.api.domain.feedback.collector.DisclosureCollector;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCollectionService {

	private final NewsCollector newsCollector;

	private final DisclosureCollector disclosureCollector;

	private final InstrumentService instrumentService;

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final Clock clock;

	@Scheduled(cron = "${feedback.news.collect-cron}", zone = "Asia/Seoul")
	public void collectNews() {
		int saved = 0;
		int failed = 0;
		for (Market market : Market.values()) {
			List<Instrument> instruments = instrumentService.getRealInstrumentEntities(market);
			List<String> sameMarketNames = instruments.stream().map(Instrument::getName).toList();
			for (Instrument instrument : instruments) {
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

	public int collectForInstrument(Instrument instrument) {
		List<Instrument> sameMarket = instrumentService.getRealInstrumentEntities(instrument.getMarket());
		List<String> sameMarketNames = sameMarket.stream().map(Instrument::getName).toList();
		List<CollectedNewsDto> collected = newsCollector.collect(instrument, sameMarketNames);
		return save(instrument, MarketNewsItemType.NEWS, collected);
	}

	@Scheduled(cron = "${feedback.news.disclosure-cron}", zone = "Asia/Seoul")
	public void collectDisclosures() {
		LocalDate collectionDate = LocalDate.now(clock);
		int saved = 0;
		int failed = 0;
		for (Instrument instrument : instrumentService.getRealInstrumentEntities(Market.STOCK)) {
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
					LocalDateTime.now(clock)));
				saved++;
			} catch (DataIntegrityViolationException ex) {
				absorbOnlyDuplicateRow(instrument, item, ex);
			}
		}
		return saved;
	}

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
