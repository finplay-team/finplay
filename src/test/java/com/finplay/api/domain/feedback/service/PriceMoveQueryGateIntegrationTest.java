// 고정 Clock + 실 MySQL로 카드 조회의 노출 게이트(§C-5)와 재재생 동작을 검증한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveListItemResponse;
import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 완료 조건 노출 게이트 ①②⑥과 API 계약 ②의 세 갈래가 이 파일의 목표다. 인증(계약 ①)과 직렬화는
// PriceMoveControllerTest가 맡는다.
//
// 게이트는 저장된 TIME 값과 현재 벽시계의 비교라, mock으로는 "어떤 인자로 물었는가"밖에 볼 수 없다.
// 특히 게이트 ⑥(같은 원본 거래일 재재생)은 실제로 저장된 행 위에서만 드러난다 — 그래서 실 DB다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (NewsCollectionIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class PriceMoveQueryGateIntegrationTest {

	// 원본 거래일 D = 2026-08-05(수). 직전 영업일은 2026-08-04(화)다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	// 같은 원본 거래일을 두 번 재생한다 — 수집이 하루 실패하면 스케줄러가 과거로 거슬러 올라가 이렇게 된다.
	private static final LocalDate FIRST_REPLAY_DATE = LocalDate.of(2026, 8, 6);
	private static final LocalDate SECOND_REPLAY_DATE = LocalDate.of(2026, 8, 7);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";
	private static final String CRYPTO_SYMBOL = "BTC";

	@Autowired
	private PriceMoveQueryService priceMoveQueryService;

	@Autowired
	private PriceMoveCardService priceMoveCardService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(INITIAL_NOW);
		instrument = stockInstrument();
	}

	private Instrument stockInstrument() {
		return instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
	}

	private void saveReadySession(LocalDate serviceDate) {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			serviceDate,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(serviceDate, LocalTime.of(8, 40)),
			LocalDateTime.of(serviceDate, LocalTime.of(8, 0))));
	}

	private PriceMoveEvent saveCard(LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return saveCard(PriceMoveEventType.INTRADAY, windowStart, windowEnd, revealTime);
	}

	private PriceMoveEvent saveCard(
		PriceMoveEventType eventType, LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return priceMoveEventRepository.save(PriceMoveEvent.createStock(
			instrument,
			eventType,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			eventType + " " + windowStart + " 카드",
			NarrativeSource.LLM,
			revealTime,
			INITIAL_NOW));
	}

	private MarketNewsItem saveNews(String title, LocalDateTime publishedAt) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.test/gate/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	private List<PriceMoveListItemResponse> queryMoves() {
		return priceMoveQueryService.getPriceMoves(instrument.getId()).moves();
	}

	// --- 게이트 ① reveal_time이 지나지 않은 카드는 조회에서 빠진다 ---

	@Test
	@DisplayName("11:30에는 revealTime 11:26 카드만 보이고 14:10 카드는 안 보인다")
	void hidesCardsWhoseRevealTimeHasNotPassedYet() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.of(11, 26));
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 30)));

		assertThat(queryMoves())
			.extracting(PriceMoveListItemResponse::windowEnd)
			.containsExactly(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
	}

	// 경계가 <= 이므로 정각에 열린다. 부등호가 < 로 바뀌면 카드가 1분 늦게 열리고 아무도 모른다.
	@Test
	@DisplayName("revealTime 정각에 카드가 열리고 1초 전에는 닫혀 있다")
	void opensExactlyAtRevealTime() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.of(11, 26));

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 25, 59)));
		assertThat(queryMoves()).isEmpty();

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 26, 0)));
		assertThat(queryMoves()).hasSize(1);
	}

	// --- 게이트 ⑥ 같은 원본 거래일이 두 번 재생돼도 게이트가 작동한다 (이 파일의 핵심) ---

	// 카드는 한 벌만 만들고 재생세션만 두 개 심는다. reveal_time을 절대 시각(D 14:10)으로 저장하는 구현이면
	// 두 번째 재생일 오전에는 그 시각이 이미 과거라 오후 카드가 통째로 열린다.
	@Test
	@DisplayName("두 번째 재생일 오전 10:00에 오후 카드가 보이지 않는다")
	void keepsAfternoonCardHiddenOnTheMorningOfASecondReplay() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		PriceMoveEvent afternoonCard = saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(10, 0)));

		// 픽스처 전제를 실제 저장값으로 확인한다 — 절대 시각 게이트라면 이미 열려 있어야 하는 상태다.
		LocalTime storedRevealTime = priceMoveEventRepository.findById(afternoonCard.getId()).orElseThrow()
			.getRevealTime();
		LocalDateTime asAbsoluteTime = LocalDateTime.of(ORIGIN_TRADE_DATE, storedRevealTime);
		assertThat(asAbsoluteTime).isBefore(LocalDateTime.now(mutableClock));

		// 같은 행에 절대 시각 게이트를 실제 쿼리로 걸어 본다 — (원본 거래일 + reveal_time) <= now.
		// 이 쪽은 카드를 잡는다. 즉 이 픽스처는 두 구현을 실제로 갈라 놓는다.
		Long openedByAbsoluteTimeGate = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM price_move_events "
				+ "WHERE id = ? AND TIMESTAMP(origin_trade_date, reveal_time) <= ?",
			Long.class,
			afternoonCard.getId(),
			LocalDateTime.now(mutableClock));
		assertThat(openedByAbsoluteTimeGate).isEqualTo(1L);

		// 그런데도 닫혀 있어야 한다 — TIME으로 저장하고 벽시계 시각과 비교하기 때문이다.
		assertThat(queryMoves()).isEmpty();
	}

	@Test
	@DisplayName("두 번째 재생일에도 오후 시각이 되면 같은 카드가 열린다 — 대칭이 맞는다")
	void opensTheSameCardOnTheAfternoonOfASecondReplay() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves()).hasSize(1);
	}

	// 조회한 날짜를 붙이는 구현이면 두 번째 재생일에 응답의 날짜가 원본 거래일과 어긋난다.
	@Test
	@DisplayName("windowStart·windowEnd에 붙는 날짜가 조회 날짜가 아니라 원본 거래일이다")
	void stampsWindowTimesWithTheOriginTradeDateNotTheQueryDate() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(instrument.getId());

		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(response.moves()).singleElement().satisfies(move -> {
			assertThat(move.windowStart())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 5)));
			assertThat(move.windowEnd())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 10)));
			// 조회 날짜(두 번째 재생일)가 붙으면 이 두 단정이 깨진다.
			assertThat(move.windowStart().toLocalDate()).isNotEqualTo(SECOND_REPLAY_DATE);
			assertThat(move.windowEnd().toLocalDate()).isNotEqualTo(SECOND_REPLAY_DATE);
		});
	}

	// --- 게이트 ② 직전 거래일 저녁 기사를 근거로 붙인 갭 카드가 개장 직후에 보인다 ---

	// 카드를 손으로 심지 않고 확정 경로로 만든다 — revealTime이 4번의 클램프로 09:00이 되는지까지 함께 본다.
	// 클램프가 없으면 revealTime이 18:40이라 09:01 조회에서 카드 자체가 안 보인다.
	@Test
	@DisplayName("전일 18:40 기사를 근거로 만든 갭 카드가 09:01 조회에 나오고 발행시각이 그대로 실린다")
	void showsOpeningGapCardRightAfterMarketOpenWithItsPreviousEveningSource() {
		saveReadySession(SECOND_REPLAY_DATE);
		LocalDateTime previousEvening = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40));
		saveNews("전일 저녁 기사", previousEvening);
		PriceMoveEvent gapCard = priceMoveCardService.confirmStockCard(
			instrument,
			ORIGIN_TRADE_DATE,
			new PriceMoveDetectionDto(
				PriceMoveEventType.OPENING_GAP,
				LocalTime.of(9, 3),
				LocalTime.of(9, 3),
				new BigDecimal("0.030000"),
				new BigDecimal("3.0000")))
			.orElseThrow();
		assertThat(gapCard.getRevealTime()).isEqualTo(LocalTime.of(9, 0));

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(9, 1)));

		assertThat(queryMoves()).singleElement().satisfies(move -> {
			assertThat(move.eventType()).isEqualTo(PriceMoveEventType.OPENING_GAP);
			assertThat(move.sources()).extracting(NewsItem::publishedAt).containsExactly(previousEvening);
			assertThat(move.sources()).extracting(NewsItem::title).containsExactly("전일 저녁 기사");
		});
	}

	// --- API 계약 ② 빈 응답 세 갈래 ---

	@Test
	@DisplayName("카드가 0건이면 originTradeDate는 있고 moves는 빈 배열이며 status는 EMPTY다")
	void returnsOriginTradeDateWithEmptyMovesWhenThereIsNoCard() {
		saveReadySession(SECOND_REPLAY_DATE);

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(instrument.getId());

		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(response.moves()).isEmpty();
		// 세션이 READY면 카드가 0건이어도 NOT_YET이 아니다 — 이 단정이 없으면 "주식 READY 경로가 EMPTY를
		// 내는 팩터리를 탄다"가 컨트롤러 테스트의 stub에서만 간접 고정된다 (PR #283 리뷰).
		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
	}

	// 세션 행 없음 / PREPARING / FAILED 셋 다 같은 답이다 — 어떤 거래일을 재생 중인지 자체가 확정되지 않았다.
	@Test
	@DisplayName("재생세션이 없거나 PREPARING·FAILED면 originTradeDate가 null이고 빈 배열이다")
	void returnsNullOriginTradeDateForEveryNotReadySessionState() {
		saveCard(LocalTime.of(9, 0), LocalTime.of(9, 5), LocalTime.of(9, 6));

		// ① 행 자체가 없다
		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());

		// ② PREPARING
		stockReplaySessionRepository.save(StockReplaySession.preparing(
			SECOND_REPLAY_DATE, ORIGIN_TRADE_DATE,
			LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(8, 0))));
		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());

		// ③ FAILED — 다른 서비스 날짜로 심고 시계를 그 날짜로 옮긴다(service_date가 유니크다).
		stockReplaySessionRepository.save(StockReplaySession.failed(
			FIRST_REPLAY_DATE, ORIGIN_TRADE_DATE,
			LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(8, 30)), "NO_DATA",
			LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(8, 0))));
		mutableClock.set(LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(15, 0)));
		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());
	}

	// 코인은 재생 시간축이 없어 originTradeDate가 항상 null이다 — 예외가 아니라 빈 목록이다(plan.md 8번 소유).
	@Test
	@DisplayName("코인 종목은 예외 없이 originTradeDate가 null이고 빈 배열이다")
	void returnsEmptyResponseForCryptoInstrument() {
		saveReadySession(SECOND_REPLAY_DATE);
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();

		assertThat(priceMoveQueryService.getPriceMoves(crypto.getId()))
			.isEqualTo(PriceMoveListResponse.of(null, List.of()));
	}

	// Issue #280 — 이 파일이 두 상황을 실 DB 위에서 나란히 만들 수 있는 유일한 자리다. 위 두 테스트가 각각
	// notYet()·of(null, [])와 같은지만 보므로, 둘이 서로 다르다는 사실은 여기서 직접 못 박는다. status를 빼면
	// 두 응답이 다시 같아지면서 이 테스트만 깨진다.
	@Test
	@DisplayName("주식 재생세션 미준비(NOT_YET)와 코인 카드 0건(EMPTY)이 응답만으로 구별된다")
	void distinguishesNotReadyStockFromCryptoByStatusAlone() {
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();

		// 재생세션 행이 없는 상태 — 주식은 미준비, 코인은 애초에 재생세션과 무관하다.
		PriceMoveListResponse stockResponse = priceMoveQueryService.getPriceMoves(instrument.getId());
		PriceMoveListResponse cryptoResponse = priceMoveQueryService.getPriceMoves(crypto.getId());

		// 이 두 줄이 이슈에 실린 실측이다 — status가 없던 시절 두 응답을 같게 만들던 필드들.
		assertThat(stockResponse.originTradeDate()).isNull();
		assertThat(cryptoResponse.originTradeDate()).isNull();
		assertThat(stockResponse.moves()).isEmpty();
		assertThat(cryptoResponse.moves()).isEmpty();

		assertThat(stockResponse.status()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(cryptoResponse.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(stockResponse).isNotEqualTo(cryptoResponse);
	}

	// --- 8개 이슈 공통 조건: 원장 불변 (코인 분기, tasks.md 5번 항목) ---

	// 다른 원장 불변 테스트(CryptoPriceMoveWatcherIntegrationTest·PostSellFeedbackNarrativeIntegrationTest)와
	// 같은 테이블 목록이다. 이 조회는 애초에 원장 테이블을 읽지도 않으므로(PriceMoveQueryService는 instrument·
	// price_move_events·price_move_event_sources만 본다) UPDATE로 값만 바뀌는 경로 자체가 없다 — 행 수 비교로
	// 충분하다(ai/agent-mistakes.md 2026-08-04 "원장 불변" 행의 값 비교 요구는 UPDATE가 가능한 경로에 해당한다).
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	@Test
	@DisplayName("코인 분기 조회는 카드·근거가 있어도 원장 테이블을 전혀 건드리지 않는다")
	void neverWritesLedgerTablesWhenQueryingCryptoPriceMoves() {
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		LocalDateTime occurredAt = INITIAL_NOW.minusHours(1);
		PriceMoveEvent card = priceMoveEventRepository.save(PriceMoveEvent.createCrypto(
			crypto, occurredAt, new BigDecimal("0.031000"), new BigDecimal("3.4000"),
			"대형 거래소 상장 소식이 있었습니다.", NarrativeSource.TEMPLATE, INITIAL_NOW));
		MarketNewsItem news = marketNewsItemRepository.save(MarketNewsItem.create(
			crypto, MarketNewsItemType.NEWS, "코인 원장 불변 기사", "coindesk.com",
			"https://news.example.test/ledger-invariance", occurredAt.minusMinutes(10), INITIAL_NOW));
		priceMoveEventSourceRepository.save(PriceMoveEventSource.of(card, news));

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(crypto.getId());

		// 실제로 카드·근거를 읽었는데도(대역이 아니라) 원장은 그대로여야 의미가 있다.
		assertThat(response.moves()).hasSize(1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	// --- 순서 (계약에 명시가 없어 회귀 방지용) ---

	// 첫 분봉이 09:00인 날에는 갭 카드의 windowStart(= 첫 분봉 시각)와 장중 첫 후보의 windowStart(= t − W)가
	// 정확히 같아진다. 유니크 제약에 event_type을 넣은 이유로 §데이터 모델이 든 바로 그 상황이며, 2차 정렬 키가
	// 없으면 그날 응답 순서가 DB 임의 순서라 화면이 실행마다 달라진다.
	//
	// 서로 다른 windowStart만 쓰는 다른 정렬 테스트로는 이 자리가 검증되지 않는다 — 2차 키가 없어도 전부 통과한다.
	@Test
	@DisplayName("windowStart가 똑같이 09:00인 갭·장중 카드가 저장 순서(id)대로 내려온다")
	void breaksWindowStartTiesByIdWhichIsCreationOrder() {
		saveReadySession(SECOND_REPLAY_DATE);
		// 배치는 §C-6의 3단계(갭) → 4단계(장중) 순으로 돌므로 갭 카드가 먼저 저장된다.
		PriceMoveEvent gap = saveCard(PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0), LocalTime.of(9, 0),
			LocalTime.of(9, 0));
		PriceMoveEvent intraday = saveCard(PriceMoveEventType.INTRADAY, LocalTime.of(9, 0), LocalTime.of(9, 5),
			LocalTime.of(9, 6));
		// 픽스처 전제 — 두 windowStart가 실제로 같은 값이어야 2차 키를 검증한다.
		assertThat(gap.getWindowStart()).isEqualTo(intraday.getWindowStart()).isEqualTo(LocalTime.of(9, 0));
		assertThat(gap.getId()).isLessThan(intraday.getId());
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves())
			.extracting(PriceMoveListItemResponse::eventType)
			.containsExactly(PriceMoveEventType.OPENING_GAP, PriceMoveEventType.INTRADAY);
	}

	// 대조군 — 2차 키가 id(= 생성 순서)이므로 저장 순서를 뒤집으면 응답 순서도 뒤집힌다. event_type으로 가르는
	// 구현이었다면 저장 순서와 무관하게 항상 같은 순서가 나오므로(문자열 정렬로 INTRADAY < OPENING_GAP) 위
	// 테스트와 이 테스트를 동시에 만족시킬 수 없다.
	@Test
	@DisplayName("저장 순서를 뒤집으면 응답 순서도 뒤집힌다 — 2차 키가 event_type이 아니라 id다")
	void tieOrderFollowsInsertionOrderNotEventType() {
		saveReadySession(SECOND_REPLAY_DATE);
		PriceMoveEvent intraday = saveCard(PriceMoveEventType.INTRADAY, LocalTime.of(9, 0), LocalTime.of(9, 5),
			LocalTime.of(9, 6));
		PriceMoveEvent gap = saveCard(PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0), LocalTime.of(9, 0),
			LocalTime.of(9, 0));
		assertThat(intraday.getId()).isLessThan(gap.getId());
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves())
			.extracting(PriceMoveListItemResponse::eventType)
			.containsExactly(PriceMoveEventType.INTRADAY, PriceMoveEventType.OPENING_GAP);
	}

	@Test
	@DisplayName("카드는 windowStart 오름차순이고 근거는 발행시각 내림차순이다")
	void ordersCardsByWindowStartAndSourcesByPublishedAtDesc() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveNews("이른 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		saveNews("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24)));
		priceMoveCardService.confirmStockCard(instrument, ORIGIN_TRADE_DATE, new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500")));
		saveCard(LocalTime.of(9, 0), LocalTime.of(9, 5), LocalTime.of(9, 6));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		List<PriceMoveListItemResponse> moves = queryMoves();

		assertThat(moves).extracting(move -> move.windowStart().toLocalTime())
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(11, 20));
		assertThat(moves.get(1).sources()).extracting(NewsItem::title)
			.containsExactly("늦은 기사", "이른 기사");
	}

}
