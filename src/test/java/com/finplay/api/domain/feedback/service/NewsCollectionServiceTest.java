// 수집 저장 경로 — 중복 무시·발행일자 무필터·수집 시각 기록·공시는 주식만을 검증하는 단위 테스트.
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
		when(instrumentService.getRealInstrumentEntities(Market.STOCK)).thenReturn(List.of(samsung));
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(bitcoin));
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

	// ADR-0017 §결정 2 — 온디맨드 수집도 같은 시장 종목명 목록만 넘긴다(collectNews()와 같은 제목 필터 규칙).
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

	// ADR-0017 §결정 6 — 이미 저장된 URL이면 온디맨드 수집도 다시 저장하지 않는다.
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

	// --- 종목 단위 예외 격리와 저장 시각 (이슈 #408) ---

	// 회귀: 던지는 것은 수집기가 아니라 save다. Market.values()가 STOCK → CRYPTO 순이라, 격리가 없으면 주식
	// 종목 하나의 실패가 남은 주식과 코인 전 종목 수집을 통째로 건너뛰고 @Scheduled가 삼켜 신호도 없다.
	@Test
	@DisplayName("한 종목 저장이 터져도 다음 시장까지 수집이 계속된다")
	void keepsCollectingOtherInstrumentsWhenOneSaveThrows() {
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("터지는 기사", "hankyung.com", "https://hankyung.com/a/boom",
				LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(newsCollector.collect(eq(bitcoin), any())).thenReturn(
			List.of(news("코인 기사", "coindesk.com", "https://coindesk.com/a/1",
				LocalDateTime.of(2026, 8, 5, 9, 5))));
		// 중복 흡수 경로로 새지 않도록 무결성 위반이 아닌 예외를 쓴다 — 이 단정의 대상은 종목 루프의 격리다.
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

	// 조회-후-삽입 구조라 findExistingUrls와 save 사이에 다른 실행(코인 온디맨드 수집)이 같은 기사를 넣을 수
	// 있다. 그 경합은 FEED-001의 "중복은 오류가 아니다"에 해당하므로 조용히 넘긴다.
	@Test
	@DisplayName("저장 직전 경합으로 중복이 되면 행 존재를 확인하고 조용히 넘긴다")
	void absorbsTheDuplicateRowWhenAnotherRunInsertedItFirst() {
		String url = "https://hankyung.com/a/race";
		when(newsCollector.collect(eq(samsung), any())).thenReturn(
			List.of(news("경합 기사", "hankyung.com", url, LocalDateTime.of(2026, 8, 5, 9, 0))));
		when(marketNewsItemRepository.findExistingUrls(1L, List.of(url)))
			.thenReturn(List.of()) // 첫 조회 — 아직 없다
			.thenReturn(List.of(url)); // 실패 후 재조회 — 다른 실행이 넣었다
		when(marketNewsItemRepository.save(any())).thenThrow(new DataIntegrityViolationException("중복"));

		service.collectNews();

		// 예외가 종목 루프 밖으로 나가지 않고, 재조회로 "행이 있다"를 확인한 것까지 본다.
		verify(marketNewsItemRepository, times(2)).findExistingUrls(1L, List.of(url));
	}

	// FK·NOT NULL 위반이나 utf8mb4가 거부하는 문자열은 행이 안 생긴다 — 그것까지 삼키면 그 기사는 매 회차 다시
	// 시도되면서 아무 신호도 남지 않는다. 여기서는 삼키되 WARN으로 드러나는 것이 계약이라, 예외가 루프 밖으로
	// 나가지 않으면서도 중복 판정과는 다른 경로를 탔음을 재조회 결과로 가른다.
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

	// 회귀: created_at은 배치 시작 시각이 아니라 저장 시각이다. 수집 회차가 길어지면(34종목 × 읽기 타임아웃
	// 10초) 그 사이에 돈 요약 배치의 generated_at보다 이른 값으로 저장돼, 재생성 판정
	// (existsByInstrumentIdAndCreatedAtAfter)이 그 기사를 잡지 못한다.
	//
	// 고정 Clock으로는 이 회귀가 잡히지 않는다 — 배치 시작과 저장 시각이 같은 값이라 어느 구현이든 초록이다.
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

	// --- 샌드박스 종목 제외 (이슈 #406) ---

	// 회귀: 샌드박스 튜토리얼 종목은 수집 대상이 아니다 (이슈 #406). 목록을 얻는 메서드를 되돌리면
	// `알파전자` 같은 실사명으로 외부 검색이 나가 무관한 기사가 그 종목의 것으로 저장되고, 저장 단계의
	// 제목 필터는 자기 이름 등장을 요구하지 않아 거기서도 걸리지 않는다.
	@Test
	@DisplayName("뉴스·공시 수집은 샌드박스 종목을 제외한 목록으로만 돈다")
	void collectsOnlyRealInstruments() {
		service.collectNews();
		service.collectDisclosures();

		verify(instrumentService).getRealInstrumentEntities(Market.CRYPTO);
		verify(instrumentService, times(2)).getRealInstrumentEntities(Market.STOCK);
		verify(instrumentService, never()).getInstrumentEntities(any());
	}

	// 온디맨드 수집(ADR-0017)도 같은 규칙이다 — 제목 필터의 비교 대상 이름 목록에 샌드박스 종목명이
	// 섞이면 그 이름을 담은 정상 기사가 "다른 종목 뉴스"로 걸러진다.
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
