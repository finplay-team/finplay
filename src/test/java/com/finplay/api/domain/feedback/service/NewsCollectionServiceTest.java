package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.collector.CollectedNewsDto;
import com.finplay.api.domain.feedback.collector.DisclosureCollector;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class NewsCollectionServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);

	private NewsCollector newsCollector;

	private DisclosureCollector disclosureCollector;

	private InstrumentService instrumentService;

	private MarketNewsItemRepository marketNewsItemRepository;

	private NewsCollectionService service;

	private Instrument samsung;

	private Instrument bitcoin;

	@BeforeEach
	void setUp() {
		newsCollector = mock(NewsCollector.class);
		disclosureCollector = mock(DisclosureCollector.class);
		instrumentService = mock(InstrumentService.class);
		marketNewsItemRepository = mock(MarketNewsItemRepository.class);
		Clock clock = Clock.fixed(COLLECTED_AT.atZone(KST).toInstant(), KST);
		service = new NewsCollectionService(
			newsCollector, disclosureCollector, instrumentService, marketNewsItemRepository, clock);

		samsung = instrument(1L, Market.STOCK, "005930", "삼성전자");
		bitcoin = instrument(2L, Market.CRYPTO, "BTC", "비트코인");
		when(instrumentService.getRealInstrumentEntities(Market.STOCK)).thenReturn(List.of(samsung));
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(bitcoin));
	}

	@Test
	@DisplayName("수집한 기사를 저장하고 created_at에 발행 시각이 아니라 수집 시각을 넣는다")
	void savesCollectedNewsWithCollectionTimeAsCreatedAt() {
		LocalDateTime publishedAt = LocalDateTime.of(2026, 8, 5, 10, 3);
		when(newsCollector.collect(eq(samsung), any()))
			.thenReturn(List.of(news("반도체 업황 반등", "hankyung.com", "https://hankyung.com/a/1", publishedAt)));

		service.collectNews();

		MarketNewsItem saved = captureSaved();
		assertThat(saved.getTitle()).isEqualTo("반도체 업황 반등");
		assertThat(saved.getPublisher()).isEqualTo("hankyung.com");
		assertThat(saved.getUrl()).isEqualTo("https://hankyung.com/a/1");
		assertThat(saved.getType()).isEqualTo(MarketNewsItemType.NEWS);
		assertThat(saved.getPublishedAt()).isEqualTo(publishedAt);
		assertThat(saved.getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

	@Test
	@DisplayName("어느 구간에도 걸리지 않는 발행 시각의 기사도 그대로 저장된다")
	void doesNotFilterByPublishedDate() {
		LocalDateTime longAgo = LocalDateTime.of(2026, 5, 11, 11, 0);
		LocalDateTime future = LocalDateTime.of(2026, 8, 6, 23, 59);
		when(newsCollector.collect(eq(samsung), any())).thenReturn(List.of(
			news("석 달 전 기사", "hankyung.com", "https://hankyung.com/a/old", longAgo),
			news("앞선 시각 기사", "hankyung.com", "https://hankyung.com/a/future", future)));

		service.collectNews();

		ArgumentCaptor<MarketNewsItem> captor = ArgumentCaptor.forClass(MarketNewsItem.class);
		verify(marketNewsItemRepository, org.mockito.Mockito.times(2)).save(captor.capture());
		assertThat(captor.getAllValues())
			.extracting(MarketNewsItem::getPublishedAt)
			.containsExactly(longAgo, future);
	}

	@Test
	@DisplayName("이미 저장된 (종목, url)이면 예외 없이 조용히 건너뛴다")
	void ignoresAlreadyCollectedArticleWithoutError() {
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("이미 있는 기사", "hankyung.com", "https://hankyung.com/a/dup",
				LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(marketNewsItemRepository.findExistingUrls(1L, List.of("https://hankyung.com/a/dup")))
			.thenReturn(List.of("https://hankyung.com/a/dup"));

		service.collectNews();

		verify(marketNewsItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("같은 응답 안에 같은 URL이 두 번 있으면 한 건만 저장한다")
	void savesOnlyOnceWhenTheSameUrlAppearsTwiceInOneResponse() {
		CollectedNewsDto duplicated = news("같은 기사가 두 번", "hankyung.com", "https://hankyung.com/a/same",
			LocalDateTime.of(2026, 8, 5, 9, 0));
		when(newsCollector.collect(eq(samsung), any())).thenReturn(List.of(duplicated, duplicated));

		service.collectNews();

		verify(marketNewsItemRepository).save(any());
	}

	@Test
	@DisplayName("중복 판정을 url 단독이 아니라 (종목, url)로 묻는다")
	void asksDuplicateByInstrumentAndUrl() {
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("반도체 업황 둔화", "hankyung.com", "https://hankyung.com/a/2",
				LocalDateTime.of(2026, 8, 5, 9, 0))));

		service.collectNews();

		verify(marketNewsItemRepository).findExistingUrls(1L, List.of("https://hankyung.com/a/2"));
	}

	@Test
	@DisplayName("뉴스는 전 종목에서 수집하고 같은 시장 종목명 목록만 넘긴다")
	void collectsNewsForEveryMarketWithSameMarketNamesOnly() {
		service.collectNews();

		verify(newsCollector).collect(samsung, List.of("삼성전자"));
		verify(newsCollector).collect(bitcoin, List.of("비트코인"));
	}

	@Test
	@DisplayName("공시는 주식 종목만 수집하고 코인은 부르지 않는다")
	void collectsDisclosuresForStocksOnly() {
		when(disclosureCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("주요사항보고서", "DART", "https://dart.fss.or.kr/a/1",
				LocalDateTime.of(2026, 8, 5, 0, 0))));

		service.collectDisclosures();

		verify(disclosureCollector).collect(samsung, LocalDate.of(2026, 8, 5));
		verify(disclosureCollector, never()).collect(eq(bitcoin), any());
		assertThat(captureSaved().getType()).isEqualTo(MarketNewsItemType.DISCLOSURE);
	}

	@Test
	@DisplayName("수집기가 빈 목록을 주면 저장도 조회도 하지 않는다")
	void savesNothingWhenCollectorsReturnEmpty() {
		service.collectNews();
		service.collectDisclosures();

		verify(marketNewsItemRepository, never()).save(any());
		verify(marketNewsItemRepository, never()).findExistingUrls(anyLong(), any());
	}

	@Test
	@DisplayName("collectForInstrument는 같은 시장 종목명만 넘겨 수집기를 부른다")
	void collectForInstrumentCollectsWithSameMarketNamesOnly() {
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(List.of());

		service.collectForInstrument(bitcoin);

		verify(newsCollector).collect(bitcoin, List.of("비트코인"));
	}

	@Test
	@DisplayName("collectForInstrument로 저장한 기사의 created_at도 clock 기준 수집 시각이다")
	void collectForInstrumentSavesCollectedNewsWithCollectionTimeAsCreatedAt() {
		LocalDateTime publishedAt = LocalDateTime.of(2026, 8, 5, 10, 3);
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(
			List.of(news("비트코인 급등", "coindesk.com", "https://coindesk.com/a/1", publishedAt)));

		service.collectForInstrument(bitcoin);

		MarketNewsItem saved = captureSaved();
		assertThat(saved.getPublishedAt()).isEqualTo(publishedAt);
		assertThat(saved.getCreatedAt()).isEqualTo(COLLECTED_AT);
		assertThat(saved.getType()).isEqualTo(MarketNewsItemType.NEWS);
	}

	@Test
	@DisplayName("collectForInstrument는 이미 저장된 URL이면 다시 저장하지 않는다")
	void collectForInstrumentIgnoresAlreadyCollectedArticle() {
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(
			List.of(news("이미 있는 기사", "coindesk.com", "https://coindesk.com/a/dup",
				LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(marketNewsItemRepository.findExistingUrls(2L, List.of("https://coindesk.com/a/dup")))
			.thenReturn(List.of("https://coindesk.com/a/dup"));

		int saved = service.collectForInstrument(bitcoin);

		assertThat(saved).isZero();
		verify(marketNewsItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("collectForInstrument는 실제로 저장한 건수를 반환한다")
	void collectForInstrumentReturnsActualSavedCount() {
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(List.of(
			news("비트코인 급등", "coindesk.com", "https://coindesk.com/a/1", LocalDateTime.of(2026, 8, 5, 9, 0)),
			news("비트코인 하락", "coindesk.com", "https://coindesk.com/a/2", LocalDateTime.of(2026, 8, 5, 9, 5))));

		int saved = service.collectForInstrument(bitcoin);

		assertThat(saved).isEqualTo(2);
	}

	@Test
	@DisplayName("수집 서비스가 market_news_items 리포지토리 외의 리포지토리를 주입받지 않는다")
	void holdsNoRepositoryOtherThanMarketNewsItemRepository() {
		List<String> repositoryFields = Arrays.stream(NewsCollectionService.class.getDeclaredFields())
			.map(Field::getType)
			.map(Class::getSimpleName)
			.filter(typeName -> typeName.endsWith("Repository"))
			.toList();

		assertThat(repositoryFields).containsExactly("MarketNewsItemRepository");
	}


	@Test
	@DisplayName("한 종목 저장이 터져도 다음 시장까지 수집이 계속된다")
	void keepsCollectingOtherInstrumentsWhenOneSaveThrows() {
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("터지는 기사", "hankyung.com", "https://hankyung.com/a/boom",
				LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(
			List.of(news("코인 기사", "coindesk.com", "https://coindesk.com/a/1",
				LocalDateTime.of(2026, 8, 5, 9, 5))));
		when(marketNewsItemRepository.save(argThat(
			item -> item != null && "https://hankyung.com/a/boom".equals(item.getUrl()))))
			.thenThrow(new IllegalStateException("저장 실패"));

		service.collectNews();

		ArgumentCaptor<MarketNewsItem> captor = ArgumentCaptor.forClass(MarketNewsItem.class);
		verify(marketNewsItemRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues())
			.as("주식에서 터진 뒤 코인 수집까지 도달해야 한다")
			.extracting(MarketNewsItem::getUrl)
			.contains("https://coindesk.com/a/1");
	}

	@Test
	@DisplayName("저장 직전 경합으로 중복이 되면 행 존재를 확인하고 조용히 넘긴다")
	void absorbsTheDuplicateRowWhenAnotherRunInsertedItFirst() {
		String url = "https://hankyung.com/a/race";
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("경합 기사", "hankyung.com", url, LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(marketNewsItemRepository.findExistingUrls(1L, List.of(url)))
			.thenReturn(List.of())
			.thenReturn(List.of(url));
		when(marketNewsItemRepository.save(any())).thenThrow(new DataIntegrityViolationException("중복"));

		service.collectNews();

		verify(marketNewsItemRepository, times(2)).findExistingUrls(1L, List.of(url));
	}

	@Test
	@DisplayName("행이 생기지 않은 무결성 위반도 배치를 죽이지 않는다")
	void doesNotKillTheBatchWhenTheRowWasNeverCreated() {
		String url = "https://hankyung.com/a/broken";
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("깨진 기사", "hankyung.com", url, LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(marketNewsItemRepository.findExistingUrls(1L, List.of(url))).thenReturn(List.of());
		when(marketNewsItemRepository.save(any())).thenThrow(new DataIntegrityViolationException("잘못된 문자열"));

		service.collectNews();

		verify(marketNewsItemRepository, times(2)).findExistingUrls(1L, List.of(url));
	}

	@Test
	@DisplayName("created_at은 배치 시작이 아니라 각 저장 시점의 시각이다")
	void stampsCreatedAtWhenEachArticleIsActuallySaved() {
		LocalDateTime firstSave = LocalDateTime.of(2026, 8, 5, 10, 30);
		LocalDateTime laterSave = LocalDateTime.of(2026, 8, 5, 10, 36);
		Clock advancing = mock(Clock.class);
		when(advancing.getZone()).thenReturn(KST);
		when(advancing.instant()).thenReturn(
			firstSave.atZone(KST).toInstant(), laterSave.atZone(KST).toInstant());
		NewsCollectionService advancingService = new NewsCollectionService(
			newsCollector, disclosureCollector, instrumentService, marketNewsItemRepository, advancing);
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("주식 기사", "hankyung.com", "https://hankyung.com/a/1", firstSave)));
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(
			List.of(news("코인 기사", "coindesk.com", "https://coindesk.com/a/1", firstSave)));

		advancingService.collectNews();

		ArgumentCaptor<MarketNewsItem> captor = ArgumentCaptor.forClass(MarketNewsItem.class);
		verify(marketNewsItemRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues())
			.as("배치 시작 시각을 한 번 찍어 돌려쓰면 두 값이 같아진다")
			.extracting(MarketNewsItem::getCreatedAt)
			.containsExactly(firstSave, laterSave);
	}


	@Test
	@DisplayName("뉴스·공시 수집은 샌드박스 종목을 제외한 목록으로만 돈다")
	void collectsOnlyRealInstruments() {
		service.collectNews();
		service.collectDisclosures();

		verify(instrumentService).getRealInstrumentEntities(Market.CRYPTO);
		verify(instrumentService, times(2)).getRealInstrumentEntities(Market.STOCK);
		verify(instrumentService, never()).getInstrumentEntities(any());
	}

	@Test
	@DisplayName("온디맨드 수집의 제목 필터 이름 목록도 샌드박스 종목을 제외한다")
	void onDemandCollectionAlsoUsesRealInstrumentsForTheTitleFilter() {
		service.collectForInstrument(bitcoin);

		verify(instrumentService).getRealInstrumentEntities(Market.CRYPTO);
		verify(instrumentService, never()).getInstrumentEntities(any());
	}

	private MarketNewsItem captureSaved() {
		ArgumentCaptor<MarketNewsItem> captor = ArgumentCaptor.forClass(MarketNewsItem.class);
		verify(marketNewsItemRepository).save(captor.capture());
		return captor.getValue();
	}

	private static CollectedNewsDto news(
		String title, String publisher, String url, LocalDateTime publishedAt) {
		return new CollectedNewsDto(title, publisher, url, publishedAt);
	}

	private static Instrument instrument(Long id, Market market, String symbol, String name) {
		Instrument instrument = Instrument.create(
			market, symbol, name, new BigDecimal("100"), 5000, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}
}
