// 실제 MySQL에서 market_news_items의 UNIQUE(instrument_id, url) 제약과 엔티티 매핑을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MarketNewsItemRepositoryTest {

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Instrument instrumentA;
	private Instrument instrumentB;

	private static final String URL = "https://news.example.com/article/1001";
	private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 8, 3, 10, 3, 0);
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 8, 3, 10, 30, 0);

	/** 앞 191자가 완전히 같고 그 뒤 쿼리 파라미터만 다른 URL 2건 — url(191) 접두 유니크였다면 중복 판정된다 (§C-8). */
	private static final String PREFIX_191 = buildPrefixOfLength(191);

	private static String buildPrefixOfLength(int length) {
		String head = "https://news.example.com/article/";
		return head + "a".repeat(length - head.length());
	}

	@BeforeEach
	void setUp() {
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		instrumentA = instrumentRepository.save(Instrument.create(
			Market.STOCK, "NEWS001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		instrumentB = instrumentRepository.save(Instrument.create(
			Market.STOCK, "NEWS002", "테스트종목B", new BigDecimal("100"), 180000, true, LocalDateTime.now()));
	}

	private MarketNewsItem newItem(Instrument instrument, String url) {
		return MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "반도체 업황 둔화", "테스트경제", url, PUBLISHED_AT, COLLECTED_AT);
	}

	// --- 완료 조건 ① 같은 기사 URL이 두 종목에 각각 저장된다 ---

	@Test
	@DisplayName("같은 기사 URL이라도 종목이 다르면 두 건 모두 저장된다 (url 단독 유니크였다면 실패)")
	void sameArticleUrlIsStoredForEachInstrumentSeparately() {
		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, URL));
		marketNewsItemRepository.saveAndFlush(newItem(instrumentB, URL));

		List<MarketNewsItem> all = marketNewsItemRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(item -> item.getInstrument().getId())
			.containsExactlyInAnyOrder(instrumentA.getId(), instrumentB.getId());
		assertThat(all).extracting(MarketNewsItem::getUrl).containsOnly(URL);
	}

	// --- 완료 조건 ② 앞 191자가 같고 쿼리 파라미터만 다른 URL 2건이 모두 저장된다 ---

	@Test
	@DisplayName("앞 191자가 같고 쿼리 파라미터만 다른 URL 2건이 같은 종목에 모두 저장된다 (접두 유니크였다면 실패)")
	void urlsSharingTheFirst191CharactersAreBothStoredForTheSameInstrument() {
		// 이 테스트의 전제 자체를 먼저 단정한다 — 접두 191자가 실제로 동일해야 접두 유니크를 반증할 수 있다.
		String urlWithNaverParam = PREFIX_191 + "?utm_source=naver";
		String urlWithDaumParam = PREFIX_191 + "?utm_source=daum";
		assertThat(PREFIX_191).hasSize(191);
		assertThat(urlWithNaverParam).startsWith(PREFIX_191);
		assertThat(urlWithDaumParam).startsWith(PREFIX_191);
		assertThat(urlWithNaverParam).isNotEqualTo(urlWithDaumParam);

		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, urlWithNaverParam));
		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, urlWithDaumParam));

		assertThat(marketNewsItemRepository.count()).isEqualTo(2);
		assertThat(marketNewsItemRepository.findAll()).extracting(MarketNewsItem::getUrl)
			.containsExactlyInAnyOrder(urlWithNaverParam, urlWithDaumParam);
	}

	// --- 반대 방향 — 유니크가 실제로 걸려 있음을 보인다 ---

	@Test
	@DisplayName("같은 종목에 완전히 같은 URL 2건째는 유니크 제약에 걸린다")
	void databaseRejectsDuplicateUrlWithinTheSameInstrument() {
		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, URL));

		MarketNewsItem duplicate = newItem(instrumentA, URL);

		assertThatThrownBy(() -> marketNewsItemRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("URL의 마지막 한 글자만 달라도 서로 다른 기사로 저장된다 (전체 컬럼 유니크)")
	void urlsDifferingOnlyInTheLastCharacterAreStoredSeparately() {
		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, URL));
		marketNewsItemRepository.saveAndFlush(newItem(instrumentA, URL + "2"));

		assertThat(marketNewsItemRepository.count()).isEqualTo(2);
	}

	// --- 엔티티 매핑 ---

	@Test
	@DisplayName("저장한 뉴스를 다시 읽으면 발행 시각과 수집 시각이 각각 보존된다")
	void savedItemKeepsPublishedAtAndCollectedAtAsDistinctValues() {
		Long id = marketNewsItemRepository.saveAndFlush(newItem(instrumentA, URL)).getId();

		MarketNewsItem found = marketNewsItemRepository.findById(id).orElseThrow();

		assertThat(found.getInstrument().getId()).isEqualTo(instrumentA.getId());
		assertThat(found.getType()).isEqualTo(MarketNewsItemType.NEWS);
		assertThat(found.getTitle()).isEqualTo("반도체 업황 둔화");
		assertThat(found.getPublisher()).isEqualTo("테스트경제");
		assertThat(found.getUrl()).isEqualTo(URL);
		// created_at은 발행 시각이 아니라 수집 시각이다 — 두 컬럼이 서로 다른 값으로 남아야 한다.
		assertThat(found.getPublishedAt()).isEqualTo(PUBLISHED_AT);
		assertThat(found.getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

	// 수집이 중복을 거르는 유일한 경로다. 단일 필드(url)만 뽑는 조회라 파생 쿼리 이름으로 선언하면 컴파일은
	// 통과하고 실행에서 변환 실패로 죽는다(docs/agent-mistakes.md 2026-08-03) — 실제 MySQL에서 한 번 태운다.
	@Test
	@DisplayName("findExistingUrls는 그 종목에 이미 저장된 URL만 돌려준다")
	void findExistingUrlsReturnsOnlyTheUrlsAlreadyStoredForThatInstrument() {
		String storedForA = "https://news.example.com/article/2001";
		String storedForB = "https://news.example.com/article/2002";
		String neverStored = "https://news.example.com/article/2003";
		marketNewsItemRepository.saveAndFlush(newsItem(instrumentA, storedForA));
		marketNewsItemRepository.saveAndFlush(newsItem(instrumentB, storedForB));

		List<String> existing = marketNewsItemRepository.findExistingUrls(
			instrumentA.getId(), List.of(storedForA, storedForB, neverStored));

		// storedForB는 다른 종목 것이라 빠진다 — 축이 url 단독이 아니라 (종목, url)이다.
		assertThat(existing).containsExactly(storedForA);
	}

	@Test
	@DisplayName("findExistingUrls는 저장된 것이 없으면 빈 목록을 준다")
	void findExistingUrlsReturnsEmptyWhenNothingWasStoredYet() {
		assertThat(marketNewsItemRepository.findExistingUrls(instrumentA.getId(), List.of(URL))).isEmpty();
	}

	private MarketNewsItem newsItem(Instrument instrument, String url) {
		return MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "반도체 업황 둔화", "테스트경제", url, PUBLISHED_AT, COLLECTED_AT);
	}

	@Test
	@DisplayName("type 컬럼에 enum 이름 문자열이 그대로 저장된다")
	void typeColumnStoresTheEnumNameAsString() {
		// ORDINAL로 매핑되면 VARCHAR(20) 컬럼에 "0"이 들어가도 MySQL은 조용히 받아들인다. 실제 저장 문자열을 확인한다.
		marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			instrumentA,
			MarketNewsItemType.DISCLOSURE,
			"주요사항보고서",
			"금융감독원",
			URL,
			PUBLISHED_AT,
			COLLECTED_AT));

		List<String> types = jdbcTemplate.queryForList("select type from market_news_items", String.class);

		assertThat(types).containsExactly("DISCLOSURE");
	}

	// --- 근거 매칭 파인더 2종 (이슈 #180 항목 3) ---

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 28);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 7, 27);

	private void save(Instrument instrument, MarketNewsItemType type, String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			type,
			title,
			"테스트경제",
			"https://news.example.com/" + instrument.getSymbol() + "/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	// 근거창은 양끝 포함이다(§C-2). BETWEEN이 실제로 양끝을 포함하는지는 실 쿼리로만 확인된다.
	@Test
	@DisplayName("발행시각 구간 조회는 양끝을 포함하고 밖의 1분은 제외한다")
	void findByPublishedAtBetweenIncludesBothEndpointsAndExcludesTheMinutesOutside() {
		save(instrumentA, MarketNewsItemType.NEWS, "09:29", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 29)));
		save(instrumentA, MarketNewsItemType.NEWS, "09:30", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		save(instrumentA, MarketNewsItemType.NEWS, "10:05", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5)));
		save(instrumentA, MarketNewsItemType.NEWS, "10:06", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 6)));

		List<MarketNewsItem> found = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.NEWS,
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5)));

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("09:30", "10:05");
	}

	@Test
	@DisplayName("발행시각 구간 조회는 종류가 다른 항목과 다른 종목을 제외한다")
	void findByPublishedAtBetweenFiltersByTypeAndInstrument() {
		LocalDateTime insideWindow = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 58));
		save(instrumentA, MarketNewsItemType.NEWS, "A 뉴스", insideWindow);
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "A 공시", insideWindow);
		save(instrumentB, MarketNewsItemType.NEWS, "B 뉴스", insideWindow);

		List<MarketNewsItem> found = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentA.getId(),
				MarketNewsItemType.NEWS,
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5)));

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("A 뉴스");
	}

	// JPQL에 FQN enum 리터럴(com.finplay.api...MarketNewsItemType.DISCLOSURE)을 쓰므로 부트스트랩만
	// 통과하고 결과가 틀릴 수 있다. 같은 날 NEWS를 함께 심어 종류 필터가 실제로 걸리는지 단정한다.
	@Test
	@DisplayName("findDisclosuresReceivedOn은 그날 접수된 공시만 주고 같은 날 뉴스는 제외한다")
	void findDisclosuresReceivedOnReturnsOnlyDisclosuresOfThatReceiptDate() {
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "D-1 접수 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(instrumentA, MarketNewsItemType.NEWS, "D-1 뉴스",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));

		List<MarketNewsItem> found = marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentA.getId(), PREVIOUS_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.atStartOfDay());

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("D-1 접수 공시");
	}

	// 경계는 [fromInclusive, toExclusive) 반열림이다 — 하루의 끝을 23:59:59로 적지 않기 위한 형태다.
	@Test
	@DisplayName("findDisclosuresReceivedOn은 from 정각을 포함하고 to 정각을 제외한다")
	void findDisclosuresReceivedOnIsHalfOpen() {
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "from 정각", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "구간 끝 직전",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(23, 59)));
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "to 정각", ORIGIN_TRADE_DATE.atStartOfDay());

		List<MarketNewsItem> found = marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentA.getId(), PREVIOUS_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.atStartOfDay());

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("from 정각", "구간 끝 직전");
	}

	@Test
	@DisplayName("findDisclosuresReceivedOn은 다른 종목의 공시를 제외하고 없으면 빈 목록이다")
	void findDisclosuresReceivedOnFiltersByInstrumentAndReturnsEmptyWhenThereIsNone() {
		save(instrumentB, MarketNewsItemType.DISCLOSURE, "B 공시", PREVIOUS_TRADE_DATE.atStartOfDay());

		assertThat(marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentA.getId(), PREVIOUS_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.atStartOfDay()))
			.isEmpty();
	}

	// --- 시장 단위 파인더 (이슈 #188 항목 4 — 개장 전 브리핑의 근거 질의) ---
	//
	// 브리핑은 시장 단일 질의라 종목별 파인더로 대체할 수 없다(§C-2-1). 두 질의 모두 JPQL에 FQN enum
	// 리터럴과 JOIN FETCH를 쓰므로 부트스트랩만 통과하고 결과가 틀릴 수 있다 — 실제로 돌려 단정한다.

	private Instrument savedCrypto() {
		return instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "NEWSBTC", "테스트코인", new BigDecimal("1"), 5000, true, LocalDateTime.now()));
	}

	@Test
	@DisplayName("findMarketNewsPublishedBetween은 시장 전체 뉴스를 주고 공시·코인·구간 밖을 제외한다")
	void findMarketNewsPublishedBetweenReturnsEveryStockNewsInTheWindow() {
		LocalDateTime from = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
		LocalDateTime to = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
		save(instrumentA, MarketNewsItemType.NEWS, "A 전장 뉴스",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		save(instrumentB, MarketNewsItemType.NEWS, "B 전장 뉴스",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(8, 30)));
		// 아래 셋은 전부 빠져야 한다 — 장중 기사가 한 건이라도 섞이면 게이트 ⑪이 깨진다.
		save(instrumentA, MarketNewsItemType.NEWS, "A 장중 뉴스",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)));
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "A 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(savedCrypto(), MarketNewsItemType.NEWS, "코인 뉴스",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketNewsPublishedBetween(Market.STOCK, from, to);

		assertThat(found).extracting(MarketNewsItem::getTitle)
			.containsExactlyInAnyOrder("A 전장 뉴스", "B 전장 뉴스");
	}

	// 경계는 양끝 포함이다. 상한을 배제로 바꾸면 09:00 정각 기사가 브리핑과 요약 양쪽에서 사라지는데,
	// 그 시각 기사는 드물어 운영에서 알아채기 어렵다.
	@Test
	@DisplayName("findMarketNewsPublishedBetween은 구간 양끝을 포함한다")
	void findMarketNewsPublishedBetweenIncludesBothBounds() {
		LocalDateTime from = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
		LocalDateTime to = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
		save(instrumentA, MarketNewsItemType.NEWS, "하한 정각", from);
		save(instrumentA, MarketNewsItemType.NEWS, "상한 정각", to);
		save(instrumentA, MarketNewsItemType.NEWS, "하한 1분 전", from.minusMinutes(1));
		save(instrumentA, MarketNewsItemType.NEWS, "상한 1분 후", to.plusMinutes(1));

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketNewsPublishedBetween(Market.STOCK, from, to);

		assertThat(found).extracting(MarketNewsItem::getTitle)
			.containsExactlyInAnyOrder("하한 정각", "상한 정각");
	}

	// 브리핑 프롬프트는 기사마다 종목명을 붙인다 — 지연 로딩이면 기사 수만큼 추가 질의가 나간다.
	// 영속성 컨텍스트를 비운 뒤에도 종목명이 읽히면 JOIN FETCH가 실제로 걸린 것이다.
	@Test
	@DisplayName("findMarketNewsPublishedBetween이 종목을 함께 가져온다")
	void findMarketNewsPublishedBetweenFetchesTheInstrument() {
		LocalDateTime from = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
		LocalDateTime to = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
		save(instrumentA, MarketNewsItemType.NEWS, "A 전장 뉴스",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketNewsPublishedBetween(Market.STOCK, from, to);

		assertThat(found).singleElement()
			.extracting(item -> item.getInstrument().getName())
			.isEqualTo("테스트종목A");
	}

	// --- 코인 재생성 판정 파인더 (이슈 #188 항목 7 — 배치 ⑩) ---
	//
	// 두 파인더 모두 created_at(수집 시각)으로 비교한다. published_at으로 비교하면 수집 주기 때문에 늦게
	// 저장된 기사가 영원히 요약에 못 들어간다(FEED-008) — 그 구분은 두 시각이 어긋난 행에서만 드러나므로
	// 아래 픽스처는 발행이 이른데 수집이 늦은 기사를 심는다.

	private void saveWithCollectedAt(
		Instrument instrument, String title, LocalDateTime publishedAt, LocalDateTime collectedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.com/collected/" + instrument.getSymbol() + "/" + title,
			publishedAt,
			collectedAt));
	}

	@Test
	@DisplayName("existsByInstrumentIdAndCreatedAtAfter는 수집 시각으로 판정한다 — 발행이 일러도 잡힌다")
	void existsByCreatedAtAfterJudgesByCollectionTimeNotPublicationTime() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		// 10:03 발행인데 10:30에 수집됐다 — published_at으로 비교하면 이 기사는 영원히 못 들어간다.
		saveWithCollectedAt(instrumentA, "늦게 수집된 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 3)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));

		assertThat(marketNewsItemRepository
			.existsByInstrumentIdAndCreatedAtAfter(instrumentA.getId(), lastGeneratedAt)).isTrue();
	}

	@Test
	@DisplayName("existsByInstrumentIdAndCreatedAtAfter는 직전 생성 이전 수집분과 다른 종목을 제외한다")
	void existsByCreatedAtAfterExcludesOlderCollectionsAndOtherInstruments() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		saveWithCollectedAt(instrumentA, "직전 생성 이전 수집",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		saveWithCollectedAt(instrumentB, "다른 종목의 새 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 10)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));

		assertThat(marketNewsItemRepository
			.existsByInstrumentIdAndCreatedAtAfter(instrumentA.getId(), lastGeneratedAt)).isFalse();
	}

	// 경계는 초과(>)다 — 같으면 직전 배치가 방금 본 기사를 새 기사로 다시 세어 매시 LLM을 부른다.
	@Test
	@DisplayName("existsByInstrumentIdAndCreatedAtAfter는 수집 시각이 기준과 같으면 false다")
	void existsByCreatedAtAfterIsStrictlyAfter() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		saveWithCollectedAt(instrumentA, "기준 정각 수집",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)), lastGeneratedAt);

		assertThat(marketNewsItemRepository
			.existsByInstrumentIdAndCreatedAtAfter(instrumentA.getId(), lastGeneratedAt)).isFalse();
	}

	// JPQL에 SELECT COUNT(n) > 0과 연관 조인을 쓰므로 부트스트랩만으로는 결과가 맞는지 알 수 없다.
	@Test
	@DisplayName("existsCollectedAfter는 그 시장에 기준 이후 수집된 기사가 있으면 참이다")
	void existsCollectedAfterFindsNewlyCollectedArticlesOfThatMarket() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		Instrument coin = savedCrypto();
		saveWithCollectedAt(coin, "코인 새 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 3)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));

		assertThat(marketNewsItemRepository.existsCollectedAfter(Market.CRYPTO, lastGeneratedAt)).isTrue();
		// 시장 조건이 빠지면 주식 기사 하나로 코인 브리핑이 매시 다시 만들어진다.
		assertThat(marketNewsItemRepository.existsCollectedAfter(Market.STOCK, lastGeneratedAt)).isFalse();
	}

	@Test
	@DisplayName("existsCollectedAfter는 기준 이후 수집분이 없으면 거짓이다")
	void existsCollectedAfterIsFalseWhenNothingWasCollectedSince() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		Instrument coin = savedCrypto();
		saveWithCollectedAt(coin, "직전 생성 이전 수집",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 50)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)));

		assertThat(marketNewsItemRepository.existsCollectedAfter(Market.CRYPTO, lastGeneratedAt)).isFalse();
	}

	@Test
	@DisplayName("findMarketDisclosuresReceivedOn은 그날 접수된 주식 공시만 주고 뉴스·코인을 제외한다")
	void findMarketDisclosuresReceivedOnReturnsOnlyStockDisclosuresOfThatReceiptDate() {
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "A D-1 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(instrumentB, MarketNewsItemType.DISCLOSURE, "B D-1 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "A D 공시", ORIGIN_TRADE_DATE.atStartOfDay());
		save(instrumentA, MarketNewsItemType.NEWS, "D-1 뉴스",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		save(savedCrypto(), MarketNewsItemType.DISCLOSURE, "코인 공시", PREVIOUS_TRADE_DATE.atStartOfDay());

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketDisclosuresReceivedOn(
			Market.STOCK, PREVIOUS_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.atStartOfDay());

		assertThat(found).extracting(MarketNewsItem::getTitle)
			.containsExactlyInAnyOrder("A D-1 공시", "B D-1 공시");
	}

	// --- 샌드박스 튜토리얼 종목 제외 (이슈 #406) ---
	//
	// 수집 단계에서 이미 막지만 여기서도 거른다. 브리핑은 전 회원이 공유하는 산출물이라 **이미 저장돼 있는
	// 행**도 새지 않아야 하기 때문이다. 픽스처가 저장까지 하는 이유가 그것이다 — 수집을 막는 것만으로는
	// 이 단정이 성립하지 않는 상태를 재현한다.

	private Instrument savedSandboxStock() {
		Instrument sandbox = Instrument.create(
			Market.STOCK, "NEWSSBX", "알파전자", new BigDecimal("100"), 10000, true, LocalDateTime.now());
		ReflectionTestUtils.setField(sandbox, "tutorialSample", true);
		return instrumentRepository.save(sandbox);
	}

	@Test
	@DisplayName("findMarketNewsPublishedBetween은 샌드박스 종목의 기사를 제외한다")
	void findMarketNewsPublishedBetweenExcludesTutorialSampleInstruments() {
		LocalDateTime from = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
		LocalDateTime to = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
		LocalDateTime publishedAt = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0));
		save(instrumentA, MarketNewsItemType.NEWS, "A 전장 뉴스", publishedAt);
		save(savedSandboxStock(), MarketNewsItemType.NEWS, "알파전자 관련 기사", publishedAt);

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketNewsPublishedBetween(Market.STOCK, from, to);

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("A 전장 뉴스");
	}

	@Test
	@DisplayName("findMarketDisclosuresReceivedOn은 샌드박스 종목의 공시를 제외한다")
	void findMarketDisclosuresReceivedOnExcludesTutorialSampleInstruments() {
		save(instrumentA, MarketNewsItemType.DISCLOSURE, "A D-1 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		save(savedSandboxStock(), MarketNewsItemType.DISCLOSURE, "알파전자 D-1 공시",
			PREVIOUS_TRADE_DATE.atStartOfDay());

		List<MarketNewsItem> found = marketNewsItemRepository.findMarketDisclosuresReceivedOn(
			Market.STOCK, PREVIOUS_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.atStartOfDay());

		assertThat(found).extracting(MarketNewsItem::getTitle).containsExactly("A D-1 공시");
	}

	// 브리핑이 담지 않는 기사로 재생성이 돌면 내용이 그대로인데 LLM 호출만 쓴다.
	@Test
	@DisplayName("existsCollectedAfter는 샌드박스 종목의 수집분을 세지 않는다")
	void existsCollectedAfterIgnoresTutorialSampleInstruments() {
		LocalDateTime lastGeneratedAt = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 5));
		saveWithCollectedAt(savedSandboxStock(), "알파전자 새 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 3)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));

		assertThat(marketNewsItemRepository.existsCollectedAfter(Market.STOCK, lastGeneratedAt)).isFalse();
	}
}
