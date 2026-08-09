// 수집 저장 경로 — 중복 무시·발행일자 무필터·수집 시각 기록·공시는 주식만을 검증하는 단위 테스트.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.collector.CollectedNewsDto;
import com.finplay.api.feedback.collector.DisclosureCollector;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
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
import org.springframework.test.util.ReflectionTestUtils;

// 규칙의 정본은 spec.md FEED-001(중복 무시·발행일자 무필터·공시는 주식만)과 §데이터 모델(created_at은 수집 시각)이다.
// 종단(실제 DB 저장·원장 불변)은 NewsCollectionIntegrationTest가 본다 — 여기서는 mock으로 분기와 인자를 본다.
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
		when(instrumentService.getInstrumentEntities(Market.STOCK)).thenReturn(List.of(samsung));
		when(instrumentService.getInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(bitcoin));
	}

	// §데이터 모델 — created_at은 발행 시각이 아니라 수집 시각이다. 요약 재생성 판정이 이 값을 본다(FEED-008).
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

	// ③ FEED-001 — 수집 단계에서 발행일자로 거르지 않는다. 어느 구간(§C-2)에도 걸리지 않는 발행 시각도 그대로 저장한다.
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

	// FEED-001 — 중복은 오류가 아니라 무시한다. 30분마다 같은 기사가 다시 조회되는 것이 정상 동작이다.
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

	// 종목당 한 번에 묻는 방식에서는 같은 응답 안의 중복을 코드가 직접 막아야 한다 — 건별로 물을 때는 앞선 save가
	// 이미 커밋돼 있어 저절로 막혔다. 유니크 위반으로 터지는 자리라 회귀하면 그 종목의 수집이 통째로 죽는다.
	@Test
	@DisplayName("같은 응답 안에 같은 URL이 두 번 있으면 한 건만 저장한다")
	void savesOnlyOnceWhenTheSameUrlAppearsTwiceInOneResponse() {
		CollectedNewsDto duplicated = news("같은 기사가 두 번", "hankyung.com", "https://hankyung.com/a/same",
			LocalDateTime.of(2026, 8, 5, 9, 0));
		when(newsCollector.collect(eq(samsung), any())).thenReturn(List.of(duplicated, duplicated));

		service.collectNews();

		verify(marketNewsItemRepository).save(any());
	}

	// 중복 판정 축은 url 단독이 아니라 (종목, url)이다 — 같은 기사가 두 종목의 검색 결과에 모두 나오기 때문이다.
	@Test
	@DisplayName("중복 판정을 url 단독이 아니라 (종목, url)로 묻는다")
	void asksDuplicateByInstrumentAndUrl() {
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("반도체 업황 둔화", "hankyung.com", "https://hankyung.com/a/2",
				LocalDateTime.of(2026, 8, 5, 9, 0))));

		service.collectNews();

		verify(marketNewsItemRepository).findExistingUrls(1L, List.of("https://hankyung.com/a/2"));
	}

	// FEED-001 — 뉴스는 주식·코인 전 종목이고, 제목 필터가 시장을 섞지 않도록 같은 시장 종목명만 넘긴다.
	@Test
	@DisplayName("뉴스는 전 종목에서 수집하고 같은 시장 종목명 목록만 넘긴다")
	void collectsNewsForEveryMarketWithSameMarketNamesOnly() {
		service.collectNews();

		verify(newsCollector).collect(samsung, List.of("삼성전자"));
		verify(newsCollector).collect(bitcoin, List.of("비트코인"));
	}

	// FEED-001·§C-3 — 공시는 주식만이다. 코인에는 공시가 없다.
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

	// 수집기 계약상 실패는 빈 목록이다 — 그때 저장이 한 건도 일어나지 않고 예외도 나가지 않는다(§실패 처리).
	@Test
	@DisplayName("수집기가 빈 목록을 주면 저장도 조회도 하지 않는다")
	void savesNothingWhenCollectorsReturnEmpty() {
		service.collectNews();
		service.collectDisclosures();

		verify(marketNewsItemRepository, never()).save(any());
		verify(marketNewsItemRepository, never()).findExistingUrls(anyLong(), any());
	}

	// ADR-0016 §결정 2 — 온디맨드 수집도 같은 시장 종목명 목록만 넘긴다(collectNews()와 같은 제목 필터 규칙).
	@Test
	@DisplayName("collectForInstrument는 같은 시장 종목명만 넘겨 수집기를 부른다")
	void collectForInstrumentCollectsWithSameMarketNamesOnly() {
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(List.of());

		service.collectForInstrument(bitcoin);

		verify(newsCollector).collect(bitcoin, List.of("비트코인"));
	}

	// §데이터 모델 — collectForInstrument도 collectNews()와 같은 규칙으로 created_at에 수집 시각을 넣는다.
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

	// ADR-0016 §결정 6 — 이미 저장된 URL이면 온디맨드 수집도 다시 저장하지 않는다.
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

	// 완료 조건 — 반환값은 실제 저장 건수와 같다.
	@Test
	@DisplayName("collectForInstrument는 실제로 저장한 건수를 반환한다")
	void collectForInstrumentReturnsActualSavedCount() {
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(List.of(
			news("비트코인 급등", "coindesk.com", "https://coindesk.com/a/1", LocalDateTime.of(2026, 8, 5, 9, 0)),
			news("비트코인 하락", "coindesk.com", "https://coindesk.com/a/2", LocalDateTime.of(2026, 8, 5, 9, 5))));

		int saved = service.collectForInstrument(bitcoin);

		assertThat(saved).isEqualTo(2);
	}

	// ④ 원장 불변의 구조적 형태 — 저장 경로가 market_news_items 밖의 리포지토리를 아예 들고 있지 않다.
	// 종목 목록도 리포지토리가 아니라 market의 서비스를 경유한다(§C-6).
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
