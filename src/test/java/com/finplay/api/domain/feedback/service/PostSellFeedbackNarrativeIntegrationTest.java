// 고정 Clock + 대역 생성기 + 실 MySQL로 매도 회고 서술의 최초 생성·재사용·템플릿 폴백과 원장 불변을 검증한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

// 이슈 #208 4번 항목의 완료 조건 중 실제 저장·재사용·원장 불변이 목표다. 상태값 ⑤의 분기와 프롬프트 조립은
// PostSellFeedbackServiceTest가, 수치·파생 사실·게이트는 reader 쪽 파일들이 맡는다.
//
// **실제 LLM을 부르지 않는다**(ADR-0011·PRD C-005). FakeNarrativeGenerator를 @Primary로 얹어 성공·실패를
// 시나리오로 고정하고 호출 횟수를 센다 — 재사용을 안 하는 구현은 호출 횟수로만 드러난다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, PostSellFeedbackNarrativeIntegrationTest.NarrativeTestConfig.class})
class PostSellFeedbackNarrativeIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	// 서비스 날짜는 stock_replay_sessions의 UNIQUE(service_date)에 걸린다. 공유 Testcontainer에 트랜잭션 없이
	// 커밋하는 테스트(CandleQueryServiceIntegrationTest가 2026-08-04·08-05를 커밋한다)와 같은 날짜를 쓰면
	// 단독 실행은 통과하고 `./gradlew build` 전체에서만 Duplicate entry로 깨진다 — 그래서 이 파일 전용
	// 연도(2031)를 쓴다. 원본 거래일은 UNIQUE 대상이 아니라 그대로 둔다.
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalDate EARLIER_SERVICE_DATE = LocalDate.of(2031, 8, 3);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	// 장 마감 뒤 조회 — 매도 후 흐름·반사실이 열린 상태에서 서술을 만든다.
	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	// 후검증 5줄을 통과하는 관찰형 문장 — 인과·권유·예측·조언·판단·가정법이 없다.
	private static final String LLM_NARRATIVE = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";

	// 조회 전후로 행이 변하면 안 되는 원장 테이블 (FeedbackBatchIntegrationTest와 같은 목록이다)
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 조회가 읽기만 해야 하는 테이블
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "stock_candles",
		"stock_replay_sessions", "market_news_items");

	// 같은 feedback 도메인이지만 이 조회의 산출물이 아닌 테이블 — 쓰기는 trade_feedbacks 하나뿐이다.
	private static final List<String> OTHER_FEEDBACK_TABLES = List.of("price_move_events",
		"price_move_event_sources", "instrument_news_summaries", "market_briefings", "price_move_peer_stats");

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@MockitoSpyBean
	private TradeFeedbackRepository tradeFeedbackRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Account account;
	private Instrument stock;
	private Holding holding;
	private StockReplaySession tradeSession;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository.saveAndFlush(User.create("post-sell-narrative@finplay.com", "hash", "narr208", VIEW_AT));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, VIEW_AT));
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208N", "테스트종목208N", BigDecimal.valueOf(100), 10_000L, true,
				VIEW_AT));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		tradeSession = saveSession(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE);

		saveCandle(BUY_TIME, "69500");
		saveCandle(LocalTime.of(11, 5), "70800");
		saveCandle(LocalTime.of(14, 20), "68100");
		saveCandle(SELL_TIME, "68500");
		saveCandle(LocalTime.of(15, 5), "69500");
		saveCandle(LAST_CANDLE_TIME, "69200");

		sellTrade = saveSellTrade(tradeSession);
		allocate(sellTrade, saveLot(tradeSession, BUY_TIME));
	}

	// --- 최초 생성 → 재사용 ---

	@Test
	@DisplayName("최초 조회에서 서술을 만들어 저장하고 재조회에서는 LLM을 다시 부르지 않는다")
	void generatesOnFirstQueryAndReusesTheStoredNarrativeAfterwards() {
		// 이슈 #212 4번 항목 이후 peerComparison은 실제 판정을 탄다 — 카드가 0건이면 NO_EVENT로 판정되어
		// (postSellFlow가 이미 READY인 이 픽스처에서) 재생성 게이트가 열려 이 테스트의 "재사용" 의도와 부딪힌다.
		// 카드를 하나 심어 확정 집계 행이 없는 NOT_YET으로 두고 게이트를 닫아, 이 테스트가 보려던 순수 재사용
		// 경로만 남긴다. 게이트가 실제로 열리는 경로는 PostSellFeedbackPeerComparisonGateIntegrationTest가 본다.
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(9, 45), LocalTime.of(9, 50),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500"), "09시 45분부터 하락했습니다.",
			NarrativeSource.LLM, LocalTime.of(9, 51), VIEW_AT));
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(first.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(first.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(first.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(second.narrativeSource()).isEqualTo(first.narrativeSource());
		// UNIQUE(trade_id) — 체결 1건당 1행이다.
		assertThat(feedbackRowCount()).isEqualTo(1L);
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> {
				assertThat(feedback.getNarrative()).isEqualTo(LLM_NARRATIVE);
				assertThat(feedback.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
				// 최초 생성이라 재생성 상태는 기본값이다 — 5번 항목이 바꾼다.
				assertThat(feedback.isNarrativeFinalized()).isFalse();
				assertThat(feedback.getRegenerationAttempts()).isZero();
				assertThat(feedback.getGeneratedAt()).isEqualTo(VIEW_AT);
			});
	}

	// --- 상태값 ⑤ · LLM 실패 (완료 조건 16·11번) ---

	// 대역 생성기에 응답을 넣지 않으면 Optional.empty()를 돌려준다 — 키 없음·타임아웃·HTTP 오류가 전부 이 하나로
	// 수렴한다. NarrativeService가 §템플릿 문장으로 흡수하는지를 그 경계 밖에서 확인하는 자리다.
	@Test
	@DisplayName("LLM이 실패하면 템플릿 문장으로 채워지고 narrativeStatus는 READY·source는 TEMPLATE이다")
	void fallsBackToTheTemplateSentenceWhenTheGeneratorFails() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		// 상수 READY만 보면 공허하다 — 실제로 문장이 채워졌는지가 이 보장의 내용이다.
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrative()).contains("68,500");
		// 실패한 문장이 저장되지 않고 템플릿이 저장된다 — 재조회도 같은 문장이다.
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> assertThat(feedback.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE));
	}

	@Test
	@DisplayName("LLM이 실패해도 수치 요약·파생 사실·매도 후 흐름이 그대로 나온다")
	void keepsEveryNumberAndDerivedFactWhenTheGeneratorFails() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyPrice()).isEqualByComparingTo("70000");
		assertThat(response.sellPrice()).isEqualByComparingTo("68500");
		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0217"));
		assertThat(response.holdingMinutes()).isEqualTo(310);
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().atClose().price()).isEqualByComparingTo("69200");
	}

	// 보유 구간 극값이 없으면 템플릿의 셋째 문장이 빠진다(§템플릿 문장) — 앞 두 문장은 원장 수치라 그때도
	// 성립하고, 그래서 narrativeStatus가 항상 READY라는 보장이 유지된다.
	@Test
	@DisplayName("sameSessionCompleted=false여도 서술이 비지 않고 READY가 유지된다")
	void keepsTheNarrativeNonEmptyWhenTheTradeSpansMultipleOriginTradeDates() {
		fakeNarrativeGenerator.enqueueFailure();
		StockReplaySession otherSession = saveSession(EARLIER_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		Trade crossSessionSell = saveSellTrade(tradeSession);
		allocate(crossSessionSell, saveLot(otherSession, BUY_TIME));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(
			owner.getId(), crossSessionSell.getId());

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.postSellFlow()).isNull();
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
	}

	// --- 8개 이슈 공통 조건: 원장 불변 ---

	// tasks.md 6번 항목 — peerComparison이 실제 판정(READY)을 타는 조회도 같은 보장이 성립해야 한다. 확정 집계
	// 행이 존재하면 PostSellFeedbackReader.buildPeerComparison이 PriceMovePeerStatRepository를 읽어 응답을
	// 채우는데, 그 경로도 트랜잭션(readOnly)이 끝나기 전까지 원장·피드백 테이블에 아무것도 쓰지 않아야 한다 —
	// 2번 항목의 모집단 재구성 조회(HolderPopulationQueryService)는 배치(PeerStatsBatchService)에서만 불리고
	// 이 조회 경로에서는 호출되지 않지만, 이 테스트는 peerComparison이 실제로 채워지는 상태에서도 조회 전체가
	// 읽기 전용임을 종단으로 확인한다.
	@Test
	@DisplayName("peerComparison이 READY인 조회도 trade_feedbacks에만 1행을 쓰고 나머지는 그대로다")
	void neverWritesOutsideTradeFeedbacksWhenPeerComparisonIsReady() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);
		PriceMoveEvent card = priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(9, 45), LocalTime.of(9, 50),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500"), "09시 45분부터 하락했습니다.",
			NarrativeSource.LLM, LocalTime.of(9, 51), VIEW_AT));
		// holderCount=5 — INSUFFICIENT_SAMPLE 경계(<5)를 넘겨 READY로 판정되게 한다(§C-4).
		priceMovePeerStatRepository.saveAndFlush(
			PriceMovePeerStat.create(card, TRADE_SERVICE_DATE, 5, 2, 20, VIEW_AT));

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long feedbacksBefore = feedbackRowCount();

		PostSellFeedbackResponse response = getPostSellFeedback();

		// peerComparison이 실제로 채워졌는데도(대역 상수가 아니라) 나머지 단정이 성립해야 의미가 있다.
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.peerComparison().holderCount()).isEqualTo(5);
		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		// 행 수만 보면 값이 바뀐 UPDATE를 놓친다 — 계좌 잔액·lot 잔여수량을 값으로 비교한다.
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	@Test
	@DisplayName("회고 조회는 trade_feedbacks에만 1행을 쓰고 원장·읽기 전용·다른 피드백 테이블은 그대로다")
	void neverWritesOutsideTradeFeedbacks() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		Map<String, Object> tradeRowBefore = tradeRow();
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long feedbacksBefore = feedbackRowCount();

		getPostSellFeedback();

		// 실제로 쓰기가 일어났는데도 나머지가 그대로여야 의미가 있다.
		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		// 행 수만 보면 값이 바뀐 UPDATE를 놓친다 — 참조한 체결 행의 모든 컬럼을 그대로 비교한다.
		assertThat(tradeRow()).isEqualTo(tradeRowBefore);
		// 행 수·체결 행만 보면 잔액·lot 잔여수량이 바뀐 UPDATE를 놓친다 — 원장의 변경 가능 컬럼을 함께 비교한다.
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	// --- UNIQUE(trade_id) 동시 삽입 ---

	// 새로고침 연타면 두 요청이 각자 "기존 행 없음"을 보고 저장을 시도한다. 조회가 항상 빈 Optional을 주도록
	// 스파이로 고정해 그 상태를 재현한다 — 흡수하지 않으면 두 번째 조회가 500이 된다.
	@Test
	@DisplayName("기존 행을 못 본 상태로 두 번째 저장이 시도돼도 예외 없이 서술이 내려간다")
	void absorbsTheUniqueViolationWhenTheExistingRowIsNotSeen() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE).enqueue(LLM_NARRATIVE);
		getPostSellFeedback();
		assertThat(feedbackRowCount()).isEqualTo(1L);
		// 두 번째 조회가 기존 행을 보지 못하게 만든다 — 동시 요청이 같은 상태다.
		doReturn(Optional.empty()).when(tradeFeedbackRepository).findByTradeId(sellTrade.getId());

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private long feedbackRowCount() {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM trade_feedbacks WHERE trade_id = ?", Long.class, sellTrade.getId());
	}

	/**
	 * 원장에서 값이 바뀔 수 있는 자리 — 계좌 잔액과 lot 잔여수량이다. 행 수 비교로는 UPDATE가 잡히지 않는다.
	 *
	 * <p><b>{@code flush()}가 이 단정의 전제다.</b> 조회 경로가 관리 상태 엔티티를 더럽히기만 하면 UPDATE는
	 * 커밋 시점까지 보류되는데, 이 클래스는 트랜잭션 안에서 raw JDBC로 읽으므로 flush 없이는 그 변경이 보이지
	 * 않아 <b>원장을 건드린 구현도 초록이 된다.</b>
	 */
	private List<Map<String, Object>> mutableLedgerValues() {
		entityManager.flush();
		List<Map<String, Object>> rows = new ArrayList<>(
			jdbcTemplate.queryForList("SELECT id, cash_balance FROM accounts ORDER BY id"));
		rows.addAll(jdbcTemplate.queryForList(
			"SELECT id, remaining_quantity FROM holding_lots ORDER BY id"));
		return rows;
	}

	private Map<String, Object> tradeRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap("SELECT * FROM trades WHERE id = ?", sellTrade.getId());
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private StockReplaySession saveSession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt));
	}

	private void saveCandle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", VIEW_AT));
	}

	private HoldingLot saveLot(StockReplaySession session, LocalTime executedTime) {
		LocalDateTime executedAt = LocalDateTime.of(session.getServiceDate(), executedTime);
		Trade buyTrade = saveTrade(
			session, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"), null, executedAt);
		return holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L, executedAt, VIEW_AT));
	}

	private Trade saveSellTrade(StockReplaySession session) {
		return saveTrade(
			session, OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"), -15_207L,
			LocalDateTime.of(session.getServiceDate(), SELL_TIME));
	}

	private void allocate(Trade sell, HoldingLot lot) {
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sell, lot, new BigDecimal("10"), 700_000L, 105L, VIEW_AT));
	}

	private Trade saveTrade(
		StockReplaySession session,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, side, price, quantity,
			price.multiply(quantity).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}

	@TestConfiguration
	static class NarrativeTestConfig {

		// 장 마감(15:30) 뒤 조회 — 매도 후 흐름·반사실이 열린 상태에서 서술을 만든다.
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		// 실제 OpenAI를 부르지 않는다 (ADR-0011·PRD C-005). 응답을 시나리오로 고정하고 호출 횟수를 센다.
		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
