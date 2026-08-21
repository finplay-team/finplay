// 매도 회고 서술의 최초 생성·재사용·템플릿 폴백과 저장 경계 오케스트레이션을 검증하는 단위 테스트다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItemResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// 이슈 #208 4번 항목이 소유한 완료 조건이다 — 상태값 ⑤(LLM 실패에도 READY·TEMPLATE)와 "LLM 실패에도 수치·
// 파생 사실이 200". 종단(최초 생성 → 재사용, 원장 불변)은 PostSellFeedbackNarrativeIntegrationTest가 실제
// 대역 생성기와 실 DB로 맡고, 수치·파생 사실·게이트 자체는 reader 쪽 세 파일이 맡는다.
//
// narrativeStatus는 상수 READY라 그것만 보는 단정은 공허하다 — 그래서 이 파일은 "서술이 실제로 채워지는가"와
// "LLM을 몇 번 부르는가"를 함께 본다. 폴백 문장 자체는 NarrativeService의 책임이라 여기서는 그 결과를 대역으로
// 고정한다(실제 외부 API를 부르지 않는다, ADR-0011).
class PostSellFeedbackServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 16, 0);

	private static final String LLM_NARRATIVE = "09시 30분 매수는 이날 하락 구간보다 1시간 55분 앞섰습니다.";
	private static final String TEMPLATE_NARRATIVE = "09시 30분에 70,000원에 매수해 14시 40분에 68,500원에 매도했습니다.";

	// 이 파일의 픽스처에는 투자일기가 없어 지문이 저장된 값과 늘 같다 — 그래서 성립하는 사유는 이 파일이 원래
	// 재현하던 흐름·집단(§C-5 게이트) 하나뿐이다. 일기 사유 쪽은 tester가 따로 덮는다.
	private static final RegenerationReasons GATE_REASON_ONLY = new RegenerationReasons(false, true);

	// 나머지 두 조합 — 아래 일기 사유 케이스가 쓴다. 인자 순서가 뒤바뀌면 journal_regenerations와
	// regeneration_attempts가 조용히 맞바뀌므로(RegenerationReasons Javadoc) 조합을 이름으로 고정한다.
	private static final RegenerationReasons JOURNAL_REASON_ONLY = new RegenerationReasons(true, false);

	private static final RegenerationReasons BOTH_REASONS = new RegenerationReasons(true, true);

	// 지문은 SHA-256 hex 64자다(§FEED-013 결정 3) — 값 자체는 대조에만 쓰이므로 길이만 컬럼 제약과 맞춘다.
	private static final String STORED_FINGERPRINT = "a".repeat(64);

	private static final String CURRENT_FINGERPRINT = "b".repeat(64);

	private static final String SELL_JOURNAL = "손절 라인을 지켰습니다.";

	private static final String BUY_JOURNAL = "실적 발표 전에 담았습니다.";

	private final PostSellFeedbackReader postSellFeedbackReader = mock(PostSellFeedbackReader.class);

	private final PostSellFeedbackContextReader postSellFeedbackContextReader = mock(
		PostSellFeedbackContextReader.class);

	// 이 파일의 픽스처에는 투자일기가 없다 — 저장된 지문도 현재 지문도 null이라 일기 사유는 성립하지 않고,
	// 기존 케이스가 재현하던 흐름·집단 사유만 남는다(§FEED-013 결정 3).
	private final PostSellJournalReader postSellJournalReader = mock(PostSellJournalReader.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final TradeFeedbackWriter tradeFeedbackWriter = mock(TradeFeedbackWriter.class);

	private final TradeFeedbackRepository tradeFeedbackRepository = mock(TradeFeedbackRepository.class);

	// 재생성 누적 상한만 쓰이므로 나머지는 application.yml 기본값과 같은 값으로 둔다(§C-7).
	private static final FeedbackLlmProperties LLM_PROPERTIES = new FeedbackLlmProperties("gpt-5.4-mini", 20, 1024, 1,
		3, 3);

	private final PostSellFeedbackService postSellFeedbackService = new PostSellFeedbackService(
		postSellFeedbackReader, postSellFeedbackContextReader, postSellJournalReader, narrativeService,
		tradeFeedbackWriter, tradeFeedbackRepository, LLM_PROPERTIES, Clock.fixed(NOW.atZone(KST).toInstant(), KST));

	@BeforeEach
	void stubEmptyJournals() {
		when(postSellJournalReader.read(SELL_TRADE_ID)).thenReturn(JournalDigestDto.empty());
	}

	// --- 최초 생성 ---

	@Test
	@DisplayName("기존 서술이 없으면 만들어 저장하고 응답에 실는다 — narrativeStatus는 READY다")
	void createsAndStoresTheNarrativeOnTheFirstQuery() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		// 저장은 그 회원·그 체결로만 나가고 생성 시각은 주입된 시계다.
		verify(tradeFeedbackWriter).create(
			eq(USER_ID), eq(SELL_TRADE_ID), eq(NarrativeResultDto.llm(LLM_NARRATIVE)), isNull(), eq(NOW));
	}

	// --- 재사용 (최초 1회만 부른다) ---

	@Test
	@DisplayName("기존 서술이 있으면 그것을 쓰고 LLM을 부르지 않으며 저장하지도 않는다")
	void reusesTheStoredNarrativeWithoutCallingTheLlmAgain() {
		givenFacts(factsWithoutNarrative());
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID))
			.thenReturn(Optional.of(storedFeedback(TEMPLATE_NARRATIVE, NarrativeSource.TEMPLATE)));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(TEMPLATE_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 두 번째 조회에서 또 부르면 사용자가 매번 2.5초를 기다리고 같은 체결의 문장이 조회마다 달라진다.
	@Test
	@DisplayName("같은 체결을 두 번 조회하면 LLM 호출이 정확히 1회이고 두 응답의 문장이 같다")
	void callsTheLlmExactlyOnceAcrossTwoQueriesOfTheSameTrade() {
		givenFacts(factsWithoutNarrative());
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		// 첫 조회는 기존 행이 없고, 두 번째 조회는 방금 저장된 행을 본다.
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM)));

		PostSellFeedbackResponse first = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);
		PostSellFeedbackResponse second = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).create(any(), any(), any(), any(), any());
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(second.narrativeSource()).isEqualTo(first.narrativeSource());
	}

	// --- 상태값 ⑤ · LLM 실패 ---

	// narrativeStatus는 상수라 무조건 통과한다 — 그래서 이 테스트의 값은 "실패해도 문장이 비지 않는다"와
	// "source가 TEMPLATE로 구분된다"에 있다. UNAVAILABLE은 이 엔드포인트에 존재하지 않는다(§C-4).
	@Test
	@DisplayName("LLM이 실패해 템플릿으로 대체돼도 narrativeStatus가 READY이고 서술이 비지 않는다")
	void keepsReadyWithTemplateSourceWhenTheLlmFails() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrativeStatus()).isNotIn(
			PostSellFeedbackStatus.NOT_YET, PostSellFeedbackStatus.NO_EVENT,
			PostSellFeedbackStatus.INSUFFICIENT_SAMPLE);
	}

	@Test
	@DisplayName("템플릿으로 대체돼도 수치 요약·파생 사실·매도 후 흐름이 그대로 응답에 남는다")
	void keepsEveryNumberAndDerivedFactWhenTheNarrativeFallsBackToTheTemplate() {
		PostSellFeedbackResponse facts = factsWithoutNarrative();
		givenFacts(facts);
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.buyPrice()).isEqualByComparingTo(facts.buyPrice());
		assertThat(response.sellPrice()).isEqualByComparingTo(facts.sellPrice());
		assertThat(response.returnRate()).isEqualByComparingTo(facts.returnRate());
		assertThat(response.holdingMinutes()).isEqualTo(facts.holdingMinutes());
		assertThat(response.holdHighPrice()).isEqualByComparingTo(facts.holdHighPrice());
		assertThat(response.sellVsHighRate()).isEqualByComparingTo(facts.sellVsHighRate());
		assertThat(response.buyToNewsMinutes()).isEqualTo(facts.buyToNewsMinutes());
		assertThat(response.priceMoves()).isEqualTo(facts.priceMoves());
		assertThat(response.postSellFlow()).isEqualTo(facts.postSellFlow());
		assertThat(response.counterfactuals()).isEqualTo(facts.counterfactuals());
		assertThat(response.peerComparison()).isEqualTo(facts.peerComparison());
	}

	// --- UNIQUE(trade_id) 동시 삽입 ---

	// 새로고침 연타면 두 요청이 각자 "기존 행 없음"을 보고 저장을 시도한다. 흡수하지 않으면 GET이 500이 된다.
	@Test
	@DisplayName("동시 삽입으로 UNIQUE(trade_id)가 충돌해도 500이 아니라 방금 만든 문장으로 200이다")
	void absorbsTheUniqueViolationRaisedByAConcurrentInsert() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_trade_feedbacks_trade_id'"));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// 위 테스트의 짝 — 같은 catch절에 들어오지만 행이 안 생긴 경우다. 무결성 위반이 중복이 아니면(FK·NOT NULL)
	// 재조회가 빈 결과이므로 저장은 실패로 남지만, 그렇다고 조회를 500으로 떨어뜨리지는 않는다. 판정을 예외 타입이
	// 아니라 "행이 실제로 있는가"로 하기 때문에 이 경로가 존재한다 — 재조회가 일어나는지까지 함께 고정한다.
	// 로그 문구는 단정하지 않는다(이 저장소에 로그 단정 관례가 없다). 행동으로 고정한다.
	@Test
	@DisplayName("중복이 아닌 무결성 위반으로 저장이 실패하고 행도 없으면 예외를 내보내지 않고 정상 응답을 준다")
	void stillReturnsTheResponseWhenANonDuplicateIntegrityViolationLeavesNoRow() {
		PostSellFeedbackResponse facts = factsWithoutNarrative();
		givenFacts(facts);
		// 최초 조회와 저장 실패 후 재조회가 모두 빈 결과다 — 중복이 아니므로 행이 생기지 않았다.
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Cannot add or update a child row: a foreign key constraint fails (`fk_trade_feedbacks_trade`)"));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		// 예외가 밖으로 나가지 않고 방금 만든 문장과 수치가 그대로 200으로 나간다.
		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.returnRate()).isEqualByComparingTo(facts.returnRate());
		assertThat(response.priceMoves()).isEqualTo(facts.priceMoves());
		// 그 경로를 실제로 밟았다 — 저장을 시도했고 실패 후 행을 다시 읽었다(최초 조회 + 재조회 = 2회).
		verify(tradeFeedbackWriter).create(eq(USER_ID), eq(SELL_TRADE_ID), any(), isNull(), eq(NOW));
		verify(tradeFeedbackRepository, times(2)).findByTradeId(SELL_TRADE_ID);
	}

	// 유니크 충돌만 흡수한다 — 다른 DB 오류를 함께 삼키면 저장이 조용히 안 되는 상태가 로그도 없이 굳는다.
	// 위 두 테스트가 catch절 안쪽을 보고, 이 테스트는 catch 폭 밖의 예외가 그대로 전파되는지를 본다.
	@Test
	@DisplayName("유니크 충돌이 아닌 저장 실패는 삼키지 않는다")
	void doesNotSwallowOtherPersistenceFailures() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("커넥션 없음"));

		assertThatThrownBy(() -> postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(IllegalStateException.class);
	}

	// --- 검증 순서 (서술 생성보다 먼저다) ---

	@Test
	@DisplayName("reader가 404·403·400으로 거부하면 LLM을 부르지 않고 저장도 하지 않는다")
	void neverGeneratesANarrativeWhenTheReaderRejectsTheRequest() {
		when(postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verifyNoInteractions(narrativeService, tradeFeedbackWriter, tradeFeedbackRepository);
	}

	// --- 프롬프트 입력 조립 ---

	@Test
	@DisplayName("프롬프트에 수치·파생 사실·카드가 실리고 반사실은 애초에 자리가 없으며 집단 비교는 null이다")
	void mapsFactsIntoThePromptInputWithoutCounterfactuals() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		PostSellPromptDto prompt = captor.getValue();
		// 시각은 날짜까지 넘긴다(이슈 #275) — 여러 날에 걸친 코인 보유에서 문장이 뒤집히기 때문이다. 다만
		// 주식은 multiDayHold가 거짓이라 프롬프트 문장에는 여전히 HH:mm만 등장한다.
		assertThat(prompt.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		assertThat(prompt.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 40)));
		assertThat(prompt.multiDayHold()).isFalse();
		assertThat(prompt.buyPrice()).isEqualByComparingTo("70000");
		assertThat(prompt.sellPrice()).isEqualByComparingTo("68500");
		assertThat(prompt.realizedPnl()).isEqualTo(-15_207L);
		assertThat(prompt.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(prompt.buyToNewsMinutes()).isEqualTo(105);
		// buyToNewsMinutes와 firstNewsAt은 같은 근거 기사 하나에서 나온다 — 한쪽만 채우면 기준 시각이 사라진다.
		assertThat(prompt.firstNewsAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		assertThat(prompt.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.windowEnd()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
			assertThat(move.minutesAfterBuy()).isEqualTo(115);
			assertThat(move.sources()).singleElement()
				.satisfies(source -> assertThat(source.disclosure()).isFalse());
		});
		assertThat(prompt.closePrice()).isEqualByComparingTo("69200");
		assertThat(prompt.sellToCloseRate()).isEqualByComparingTo("0.0102");
		// 집단 비교는 항상 NOT_YET이라 넘길 값이 없는 것이 정상 상태다 — 7번이 판정을 붙이면 값이 흘러 들어온다.
		assertThat(prompt.holderCount()).isNull();
		assertThat(prompt.soldWithin30MinRate()).isNull();
		assertThat(prompt.medianMinutesToSell()).isNull();
		assertThat(prompt.yourMinutesToSell()).isNull();
	}

	// 이 판정이 프롬프트·템플릿의 날짜 표기를 켜는 유일한 스위치다(이슈 #275) — 항상 거짓으로 되돌려도
	// 주식 픽스처만 있는 한 아무 테스트도 빨개지지 않는다.
	@Test
	@DisplayName("매수일과 매도일이 다르면 multiDayHold가 참이고 holdHighBasis가 그대로 실린다")
	void marksMultiDayHoldWhenTheBuyAndSellDatesDiffer() {
		givenFacts(crossDayFacts(true, HoldHighBasis.DAILY));
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		PostSellPromptDto prompt = captor.getValue();
		assertThat(prompt.multiDayHold()).isTrue();
		// 극값을 어느 표본으로 쟀는지가 문장의 표기를 가른다 — 응답 값이 그대로 전달돼야 한다.
		assertThat(prompt.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
	}

	// 여러 원본 거래일에 걸친 주식 매매는 벽시계 날짜가 달라도 서술할 날짜 축이 없다 — 판정의 && 왼쪽이
	// 없으면 그 매매의 문장에 원본 거래일과 무관한 날짜가 붙는다.
	@Test
	@DisplayName("sameSessionCompleted=false면 벽시계 날짜가 달라도 multiDayHold는 거짓이다")
	void keepsMultiDayHoldFalseWhenTheTradeSpansReplaySessions() {
		givenFacts(crossDayFacts(false, HoldHighBasis.MINUTE));
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().multiDayHold()).isFalse();
	}

	@Test
	@DisplayName("근거 기사가 없으면 buyToNewsMinutes와 firstNewsAt이 함께 null이다")
	void leavesBothNewsFieldsNullWhenThereIsNoSource() {
		givenFacts(factsWithoutNarrative(false));
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().buyToNewsMinutes()).isNull();
		assertThat(captor.getValue().firstNewsAt()).isNull();
		assertThat(captor.getValue().priceMoves()).isEmpty();
	}

	// --- 재생성 게이트 (완료 조건 8·10번) ---

	// 게이트가 열린 뒤 첫 조회에서 1회 갈아 끼운다. 성공하면 narrative_finalized가 닫히므로 두 번째 조회는
	// 재생성하지 않는다 — 아래 두 테스트가 짝이다.
	@Test
	@DisplayName("게이트가 열리면 첫 조회에서 재생성하고 성공 저장을 부른다")
	void regeneratesOnceWhenTheGateIsOpen() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("재생성된 문장입니다.");
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), isNull(), eq(GATE_REASON_ONLY), eq(NOW));
		verify(tradeFeedbackWriter, never()).recordFailedRegeneration(any(), any());
		verify(tradeFeedbackWriter, never()).create(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("확정된 서술은 게이트가 열려 있어도 재생성하지 않는다")
	void neverRegeneratesAnAlreadyFinalizedNarrative() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(finalizedFeedback());

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("확정된 문장입니다.");
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 완료 조건 10번 — 카드 0건이면 price_move_peer_stats 행이 애초에 생기지 않는다. 게이트를 "확정 집계 행
	// 존재"로 판정한 구현은 이 경우에 영원히 재생성하지 않는데, 운영에서 가장 흔한 경우다.
	@Test
	@DisplayName("집단 비교가 NO_EVENT·INSUFFICIENT_SAMPLE이어도 확정으로 쳐서 재생성한다")
	void treatsNoEventAndInsufficientSampleAsSettled() {
		for (PostSellFeedbackStatus settled : List.of(
			PostSellFeedbackStatus.NO_EVENT, PostSellFeedbackStatus.INSUFFICIENT_SAMPLE,
			PostSellFeedbackStatus.READY)) {
			PostSellFeedbackService service = newService();
			givenFacts(gateOpenFacts(settled));
			givenStored(pendingFeedback(0));
			givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

			PostSellFeedbackResponse response = service.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

			assertThat(response.narrative())
				.as("peerComparison.status=%s는 확정이므로 게이트가 열린다", settled)
				.isEqualTo("재생성된 문장입니다.");
		}
	}

	@Test
	@DisplayName("집단 비교가 NOT_YET이면 매도 후 흐름이 READY여도 재생성하지 않는다")
	void keepsTheGateClosedWhilePeerComparisonIsNotYet() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NOT_YET));
		givenStored(pendingFeedback(0));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 매도 후 흐름만 보고 열면 15:30~집계 사이에 조회한 사용자가 집단 비교 없는 문장으로 굳는다 — 재생성이
	// 1회뿐이라 되돌릴 기회가 없다.
	@Test
	@DisplayName("매도 후 흐름이 NOT_YET이면 집단 비교가 확정이어도 재생성하지 않는다")
	void keepsTheGateClosedWhilePostSellFlowIsNotYet() {
		givenFacts(factsWithoutNarrative(
			true, PostSellFeedbackStatus.NOT_YET, PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// --- 재생성 실패와 누적 상한 (완료 조건 9번) ---

	// 템플릿 폴백을 실패로 취급한다 — 템플릿 문장에는 매도 후 흐름·집단 비교가 없어 덮으면 서술이 빈약해진다.
	@Test
	@DisplayName("재생성이 템플릿으로 폴백하면 기존 서술을 유지하고 실패만 누적한다")
	void keepsTheStoredNarrativeWhenRegenerationFallsBackToTheTemplate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		// 기존 서술이 그대로 나간다 — 템플릿으로 덮지 않는다.
		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID, GATE_REASON_ONLY);
		verify(tradeFeedbackWriter, never()).applyRegenerated(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("누적 시도가 상한 미만이면 다시 시도한다")
	void retriesWhileTheCumulativeCountIsBelowTheLimit() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry() - 1));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID, GATE_REASON_ONLY);
	}

	// 상한에 도달하면 게이트가 열려 있어도 LLM을 부르지 않는다 — 여기서 부르면 실패하는 체결 하나가 조회마다
	// LLM을 호출하는데 응답은 정상 200이라 아무 신호도 남지 않는다. 바로 위 테스트와 짝이다.
	@Test
	@DisplayName("누적 시도가 상한에 도달하면 LLM을 부르지 않는다")
	void stopsRetryingWhenTheCumulativeLimitIsReached() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry()));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 판정 순서가 narrativeFinalized → 누적 상한 → 게이트다. 상한을 게이트 뒤에 두면 상한을 넘긴 체결이
	// 게이트가 열린 동안 계속 LLM을 부른다.
	@Test
	@DisplayName("누적 시도가 상한을 넘었으면 게이트 계산 전에 멈춘다")
	void checksTheCumulativeLimitBeforeTheGate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry() + 1));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 게이트가 열렸으면 프롬프트에 매도 후 흐름 줄이 붙는다 — 매핑을 따로 만들면 그 줄이 빠진 프롬프트로
	// 재생성해 게이트가 무의미해진다.
	@Test
	@DisplayName("재생성 프롬프트에 매도 후 흐름 값이 실린다")
	void feedsThePostSellFlowIntoTheRegenerationPrompt() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().closePrice()).isEqualByComparingTo("69200");
		assertThat(captor.getValue().sellToCloseRate()).isEqualByComparingTo("0.0102");
	}

	// --- 투자일기 사유 재생성 (§FEED-013 결정 3) ---

	// 최초 저장에서 지문이 빠지면 저장된 값이 늘 null이라 일기가 있는 체결의 모든 조회가 "지문 다름"으로
	// 판정된다 — 상한에 닿기 전까지 조회마다 LLM을 부르는데 응답은 정상 200이라 아무 신호도 남지 않는다.
	@Test
	@DisplayName("최초 생성에서 이번 프롬프트에 실린 일기의 지문을 함께 저장한다")
	void storesTheJournalFingerprintOfThePromptOnTheFirstQuery() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(tradeFeedbackWriter).create(
			eq(USER_ID), eq(SELL_TRADE_ID), eq(NarrativeResultDto.llm(LLM_NARRATIVE)), eq(CURRENT_FINGERPRINT),
			eq(NOW));
	}

	// 위 테스트의 짝 — 저장된 지문이 현재 지문과 같으면 두 번째 조회가 생성기를 부르지 않는다. 지문 저장이
	// 빠졌을 때 실제로 깨지는 것이 이 동작이다.
	@Test
	@DisplayName("일기가 그대로면 두 번째 조회에서 생성기를 부르지 않는다")
	void callsTheGeneratorOnlyOnceWhileTheJournalFingerprintStaysTheSame() {
		givenFacts(factsWithoutNarrative());
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, CURRENT_FINGERPRINT)));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);
		PostSellFeedbackResponse second = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService, times(1)).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter, never()).applyRegenerated(any(), any(), any(), any(), any());
		assertThat(second.narrative()).isEqualTo(LLM_NARRATIVE);
	}

	// 이 이슈에서 가장 조용히 틀리는 자리다 — 일기 판정이 narrative_finalized를 읽으면 게이트를 이미 통과한
	// 체결에서 일기가 영원히 반영되지 않는다. 예외도 로그도 남지 않으므로 이 테스트가 유일한 신호다.
	@Test
	@DisplayName("확정된 서술도 일기 지문이 다르면 재생성한다 — 일기 판정은 narrative_finalized를 보지 않는다")
	void regeneratesForTheJournalReasonEvenWhenTheNarrativeIsAlreadyFinalized() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(finalizedFeedback(STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("일기를 반영한 문장입니다.");
		// 확정 상태라 흐름·집단 사유는 닫혀 있다 — 성립한 사유가 일기 하나뿐이어야 확정 플래그를 건드리지 않는
		// 전이로 저장된다.
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(JOURNAL_REASON_ONLY), eq(NOW));
		verify(narrativeService, times(1)).resolvePostSellNarrative(any());
	}

	// 재생성도 최초 생성과 같은 매핑을 써야 한다 — 일기 줄이 빠진 프롬프트로 재생성하면 지문만 갱신되고
	// 일기는 반영되지 않은 채 카운터만 탄다.
	@Test
	@DisplayName("일기 사유 재생성 프롬프트에 매도·매수 회고가 실린다")
	void feedsTheJournalsIntoTheRegenerationPrompt() {
		givenFacts(factsWithoutNarrative());
		givenStored(pendingFeedback(0, STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().sellJournalContent()).isEqualTo(SELL_JOURNAL);
		assertThat(captor.getValue().buyJournals()).singleElement()
			.satisfies(line -> assertThat(line.content()).isEqualTo(BUY_JOURNAL));
	}

	// 결정 1의 사용자 — 피드백을 먼저 보고 나중에 회고를 쓴다. null에서 값으로 바뀌는 것도 "달라짐"이라
	// Objects.equals가 양쪽 null을 함께 다뤄야 이 경로가 열린다.
	@Test
	@DisplayName("저장된 지문이 null인 체결도 일기를 나중에 쓰면 재생성한다")
	void regeneratesWhenAJournalIsWrittenAfterTheNarrativeWasStoredWithoutOne() {
		givenFacts(factsWithoutNarrative());
		givenStored(pendingFeedback(0, null));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("일기를 반영한 문장입니다.");
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(JOURNAL_REASON_ONLY), eq(NOW));
	}

	// 반대 방향 — 값에서 null로 바뀌는 것도 "달라짐"이다. 한쪽만 다루면 일기를 지운 체결이 지운 일기를 계속
	// 반영한 서술을 본다.
	@Test
	@DisplayName("일기가 사라져 현재 지문이 null이 돼도 달라짐으로 보고 재생성한다")
	void regeneratesWhenTheJournalDisappearsAndTheCurrentFingerprintBecomesNull() {
		givenFacts(factsWithoutNarrative());
		givenStored(pendingFeedback(0, STORED_FINGERPRINT));
		givenJournals(JournalDigestDto.empty());
		givenGenerated(NarrativeResultDto.llm("일기 없이 다시 만든 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), isNull(), eq(JOURNAL_REASON_ONLY), eq(NOW));
	}

	// 양쪽 다 null이면 같음이다 — 일기를 한 번도 쓰지 않은 체결에서 재생성이 열리면 조회마다 LLM을 부른다.
	@Test
	@DisplayName("저장된 지문과 현재 지문이 모두 null이면 재생성하지 않는다")
	void treatsTwoNullFingerprintsAsUnchanged() {
		givenFacts(factsWithoutNarrative());
		givenStored(pendingFeedback(0, null));
		givenJournals(JournalDigestDto.empty());

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 상한 + 1회를 재현한다 — 상한 이하만 보는 테스트는 "상한을 넘겨도 계속 부른다"를 잡지 못한다. 지문이
	// 다른데 상한을 넘긴 것은 오류가 아니라 재사용이다(결정 3).
	@Test
	@DisplayName("일기 사유 누적이 상한에 도달하면 지문이 달라도 생성기를 부르지 않고 재사용한다")
	void stopsRegeneratingForTheJournalReasonAtAndBeyondTheCumulativeLimit() {
		for (int consumed : List.of(
			LLM_PROPERTIES.maxJournalRegeneration(), LLM_PROPERTIES.maxJournalRegeneration() + 1)) {
			givenFacts(factsWithoutNarrative());
			givenStored(journalConsumedFeedback(consumed, STORED_FINGERPRINT));
			givenJournals(journalsWith(CURRENT_FINGERPRINT));

			PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

			assertThat(response.narrative())
				.as("journalRegenerations=%d는 상한 이상이라 기존 서술을 그대로 쓴다", consumed)
				.isEqualTo(LLM_NARRATIVE);
		}
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	@Test
	@DisplayName("일기 사유 누적이 상한 미만이면 다시 시도한다")
	void retriesForTheJournalReasonWhileTheCumulativeCountIsBelowTheLimit() {
		givenFacts(factsWithoutNarrative());
		givenStored(journalConsumedFeedback(LLM_PROPERTIES.maxJournalRegeneration() - 1, STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(JOURNAL_REASON_ONLY), eq(NOW));
	}

	// 템플릿 문장에는 일기가 반영되지 않았다 — 그런데 지문만 갱신하면 다음 조회가 "이미 반영됐다"고 판정해
	// 그 일기가 영원히 반영되지 않는다.
	@Test
	@DisplayName("일기 사유 재생성이 템플릿으로 폴백하면 서술과 지문을 유지하고 일기 카운터만 올린다")
	void keepsTheStoredNarrativeAndFingerprintWhenTheJournalRegenerationFallsBackToTheTemplate() {
		givenFacts(factsWithoutNarrative());
		givenStored(pendingFeedback(0, STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID, JOURNAL_REASON_ONLY);
		verify(tradeFeedbackWriter, never()).applyRegenerated(any(), any(), any(), any(), any());
	}

	// --- 두 카운터가 서로를 소모하지 않는다 (불변식 2) ---

	@Test
	@DisplayName("일기 사유로 상한을 다 쓴 체결도 게이트가 열리면 흐름·집단 사유로 재생성한다")
	void stillRegeneratesForTheGateReasonAfterTheJournalLimitIsExhausted() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(journalConsumedFeedback(LLM_PROPERTIES.maxJournalRegeneration(), STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("재생성된 문장입니다.");
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(GATE_REASON_ONLY), eq(NOW));
	}

	@Test
	@DisplayName("흐름·집단 사유로 상한을 다 쓴 체결도 일기를 고치면 일기 사유로 재생성한다")
	void stillRegeneratesForTheJournalReasonAfterTheGateLimitIsExhausted() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry(), STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("일기를 반영한 문장입니다.");
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(JOURNAL_REASON_ONLY), eq(NOW));
	}

	// --- 두 사유 동시 성립 (불변식 4) ---

	// 두 번 부르면 비용이 두 배인데 두 번째 프롬프트는 첫 번째와 같은 재료라 다른 문장이 나올 이유도 없다.
	// 호출 횟수로 단정하는 것이 이 규칙을 고정하는 유일한 방법이다.
	@Test
	@DisplayName("두 사유가 동시에 성립해도 생성기를 정확히 1회 부르고 사유 둘을 함께 넘긴다")
	void callsTheGeneratorExactlyOnceWhenBothReasonsHold() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(0, STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("둘 다 반영한 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("둘 다 반영한 문장입니다.");
		verify(narrativeService, times(1)).resolvePostSellNarrative(any());
		// 카운터 둘을 모두 올리는 판단은 저장 쪽이 이 값을 보고 한다 — 여기서 조합이 틀리면 한쪽이 조용히 안 오른다.
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(BOTH_REASONS), eq(NOW));
	}

	@Test
	@DisplayName("두 사유가 동시에 성립한 재생성이 템플릿으로 폴백하면 사유 둘을 함께 실패로 누적한다")
	void recordsBothReasonsAsFailedWhenTheSharedRegenerationFallsBackToTheTemplate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(0, STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		verify(narrativeService, times(1)).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID, BOTH_REASONS);
	}

	// --- 트랜잭션 경계 (구조 단정) ---

	// LLM 호출이 중앙값 2.5초라 여기에 트랜잭션을 걸면 그 시간 동안 커넥션을 쥔다. 편의로 애노테이션을 붙이는
	// 회귀는 동작으로 드러나지 않으므로(느려지기만 한다) 구조를 단정으로 고정한다.
	@Test
	@DisplayName("PostSellFeedbackService에는 클래스·메서드 어디에도 @Transactional이 없다")
	void neverWrapsTheOrchestrationInATransaction() throws Exception {
		assertThat(PostSellFeedbackService.class.getAnnotation(Transactional.class)).isNull();
		assertThat(PostSellFeedbackService.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();

		Method entryPoint = PostSellFeedbackService.class.getMethod(
			"getPostSellFeedback", Long.class, Long.class);
		assertThat(entryPoint.getAnnotation(Transactional.class)).isNull();
		assertThat(entryPoint.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}

	// spec 046 TRADESHARE-002·003 — 커뮤니티 매매 카드 요약은 loadContext(순수 DB 조회)만으로 조립하고
	// postSellFeedbackReader.read()(뉴스·가격변동카드·반사실·집단비교, 코인이면 빗썸 REST까지)는 절대 부르지
	// 않는다 — 그 경로를 타면 CommunityPostService의 @Transactional 메서드가 REST 호출 내내 DB 커넥션을
	// 쥐는 문제가 재현된다(이슈 #282와 같은 종류).
	@Test
	@DisplayName("커뮤니티 매매 카드 요약은 loadContext의 원장 값만 조립하고 reader.read()를 부르지 않는다")
	void getTradeShareSummaryMapsFactsFromLoadContextWithoutCallingReader() {
		// Trade는 Order·Account까지 갖춰야 하는 무거운 엔티티라, 이 테스트가 실제로 읽는 필드만 스텁한다 —
		// Instrument는 실제 팩토리로 만든다(docs/conventions/code.md).
		Trade trade = mock(Trade.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 1_000L, true, NOW);
		when(trade.getInstrument()).thenReturn(instrument);
		when(trade.getPrice()).thenReturn(new BigDecimal("68500"));
		when(trade.getQuantity()).thenReturn(new BigDecimal("10"));
		when(trade.getRealizedPnl()).thenReturn(-15_207L);
		SellAllocationSummaryDto allocation = new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"), NOW.minusDays(1), null, 700_000L, 105L, new BigDecimal("10"),
			List.of());
		when(postSellFeedbackContextReader.loadContext(USER_ID, SELL_TRADE_ID))
			.thenReturn(new PostSellFeedbackContext(trade, allocation));

		TradeShareSummaryResponse response = newService().getTradeShareSummary(USER_ID, SELL_TRADE_ID);

		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.name()).isEqualTo("삼성전자");
		assertThat(response.market()).isEqualTo(Market.STOCK);
		assertThat(response.buyPrice()).isEqualByComparingTo("70000.00000000");
		assertThat(response.sellPrice()).isEqualByComparingTo("68500");
		assertThat(response.quantity()).isEqualByComparingTo("10");
		assertThat(response.realizedPnl()).isEqualTo(-15_207L);
		assertThat(response.returnRate()).isEqualByComparingTo("-0.0217");
		verifyNoInteractions(postSellFeedbackReader);
	}

	// --- 픽스처 ---

	private void givenFacts(PostSellFeedbackResponse facts) {
		when(postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID)).thenReturn(facts);
	}

	private void givenNoStoredNarrative() {
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID)).thenReturn(Optional.empty());
	}

	private void givenGenerated(NarrativeResultDto resolved) {
		when(narrativeService.resolvePostSellNarrative(any())).thenReturn(resolved);
	}

	private void givenJournals(JournalDigestDto journals) {
		when(postSellJournalReader.read(SELL_TRADE_ID)).thenReturn(journals);
	}

	/** 매도 회고 1건 + 매수 회고 1건이 실린 묶음 — 지문만 케이스마다 바꿔 대조를 가른다. */
	private static JournalDigestDto journalsWith(String fingerprint) {
		return new JournalDigestDto(
			SELL_JOURNAL,
			List.of(new JournalDigestDto.BuyJournalLine(
				11L, LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)), BUY_JOURNAL)),
			fingerprint);
	}

	private PostSellFeedbackService newService() {
		return new PostSellFeedbackService(
			postSellFeedbackReader, postSellFeedbackContextReader, postSellJournalReader, narrativeService,
			tradeFeedbackWriter, tradeFeedbackRepository, LLM_PROPERTIES,
			Clock.fixed(NOW.atZone(KST).toInstant(), KST));
	}

	private void givenStored(TradeFeedback feedback) {
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(feedback));
	}

	/** 아직 확정되지 않은 행 — 실패를 {@code attempts}회 누적한 상태를 실제 전이 메서드로 만든다. */
	private static TradeFeedback pendingFeedback(int attempts) {
		return pendingFeedback(attempts, null);
	}

	private static TradeFeedback pendingFeedback(int attempts, String fingerprint) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, fingerprint);
		for (int i = 0; i < attempts; i++) {
			feedback.recordFailedRegeneration();
		}
		return feedback;
	}

	/**
	 * 일기 사유 재생성을 {@code consumed}회 소비한 행 — 흐름·집단 쪽 카운터는 0이다. 두 카운터가 서로를 소모하지
	 * 않는지 보려면 한쪽만 태운 상태가 필요하다.
	 */
	private static TradeFeedback journalConsumedFeedback(int consumed, String fingerprint) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, fingerprint);
		for (int i = 0; i < consumed; i++) {
			feedback.countJournalRegeneration();
		}
		return feedback;
	}

	/** 재생성 게이트를 이미 통과해 확정된 행. */
	private static TradeFeedback finalizedFeedback() {
		return finalizedFeedback(null);
	}

	private static TradeFeedback finalizedFeedback(String fingerprint) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, fingerprint);
		feedback.applyRegeneratedNarrative("확정된 문장입니다.", NarrativeSource.LLM, fingerprint, NOW.minusMinutes(10));
		return feedback;
	}

	// 엔티티를 mock으로 만들지 않는다 — 실제 팩토리로 만들어 값이 담긴 객체를 쓴다(docs/conventions/code.md).
	// 이 경로는 서술 두 값만 읽으므로 연관 체결은 필요하지 않다. 지문은 null이다 — 저장 당시 프롬프트에 실린
	// 일기가 없었다는 뜻이고, PostSellJournalReader 대역이 돌려주는 현재 지문(null)과 같아 일기 사유가 닫힌다.
	private static TradeFeedback storedFeedback(String narrative, NarrativeSource source) {
		return storedFeedback(narrative, source, null);
	}

	private static TradeFeedback storedFeedback(String narrative, NarrativeSource source, String journalFingerprint) {
		return TradeFeedback.create(null, narrative, source, journalFingerprint, NOW.minusMinutes(30));
	}

	private static PostSellFeedbackResponse factsWithoutNarrative() {
		return factsWithoutNarrative(true);
	}

	private static PostSellFeedbackResponse factsWithoutNarrative(boolean withCard) {
		return factsWithoutNarrative(withCard, PostSellFeedbackStatus.READY, PostSellFeedbackStatus.NOT_YET);
	}

	/**
	 * 8월 1일 14:20 매수 → 8월 5일 09:05 매도. 기본 픽스처에서 <b>두 체결 시각과 {@code holdHighBasis},
	 * {@code sameSessionCompleted}만</b> 바꿨다 — multiDayHold 판정이 보는 값이 정확히 그 셋이다.
	 */
	private static PostSellFeedbackResponse crossDayFacts(
		boolean sameSessionCompleted, HoldHighBasis holdHighBasis) {
		PostSellFeedbackResponse base = factsWithoutNarrative();
		return new PostSellFeedbackResponse(
			base.tradeId(), base.instrumentId(), base.symbol(), base.name(),
			LocalDateTime.of(2026, 8, 1, 14, 20),
			LocalDateTime.of(2026, 8, 5, 9, 5),
			base.buyPrice(), base.sellPrice(), base.quantity(), base.fee(), base.realizedPnl(), base.returnRate(),
			base.holdingMinutes(), sameSessionCompleted, base.holdHighPrice(), base.holdHighAt(),
			base.holdLowPrice(), base.holdLowAt(), base.sellVsHighRate(), base.sellVsLowRate(),
			holdHighBasis, base.buyToNewsMinutes(), base.priceMoves(), base.postSellFlow(), base.counterfactuals(),
			base.peerComparison(), base.narrative(), base.narrativeSource(), base.narrativeStatus());
	}

	/** 재생성 게이트를 여는 픽스처 — 매도 후 흐름은 READY이고 집단 비교가 확정 상태다(§C-5). */
	private static PostSellFeedbackResponse gateOpenFacts(PostSellFeedbackStatus peerStatus) {
		return factsWithoutNarrative(true, PostSellFeedbackStatus.READY, peerStatus);
	}

	/**
	 * reader가 돌려주는 형태 — 서술 세 값이 {@code null}이고 나머지는 계약 예시 그대로다. 게이트가 열린 뒤라
	 * 매도 후 흐름·반사실이 채워져 있고 집단 비교는 {@code NOT_YET}이다.
	 */
	private static PostSellFeedbackResponse factsWithoutNarrative(
		boolean withCard, PostSellFeedbackStatus flowStatus, PostSellFeedbackStatus peerStatus) {
		boolean marketClosed = flowStatus == PostSellFeedbackStatus.READY;
		return new PostSellFeedbackResponse(
			SELL_TRADE_ID,
			1L,
			"005930",
			"삼성전자",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 40)),
			new BigDecimal("70000.00000000"),
			new BigDecimal("68500"),
			new BigDecimal("10"),
			102L,
			-15_207L,
			new BigDecimal("-0.0217"),
			310,
			true,
			new BigDecimal("70800"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)),
			new BigDecimal("68100"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 20)),
			new BigDecimal("-0.0325"),
			new BigDecimal("0.0059"),
			// 주식은 언제나 1분봉 정밀도다(§FEED-012 결정 4 — DAILY는 코인 전용).
			HoldHighBasis.MINUTE,
			withCard ? 105 : null,
			withCard ? List.of(sampleCard()) : List.of(),
			marketClosed
				? new PostSellFlow(
					PostSellFeedbackStatus.READY,
					new BigDecimal("69200"),
					LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)),
					new BigDecimal("0.0102"),
					new BigDecimal("69500"),
					LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5)))
				: new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null),
			// returnRate를 채워 둔다(이슈 #212 1번) — 값이 있어도 PostSellPromptDto에 실릴 자리가 애초에 없다는
			// 것을 이 파일의 mapsFactsIntoThePromptInputWithoutCounterfactuals가 확인한다.
			new Counterfactuals(
				PostSellFeedbackStatus.READY,
				new CounterfactualScenario(
					new BigDecimal("69200"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)),
					new BigDecimal("-0.0117")),
				new CounterfactualScenario(
					new BigDecimal("70800"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)),
					new BigDecimal("0.0111")),
				null),
			new PeerComparison(peerStatus, null, null, null, null, null),
			null,
			null,
			null);
	}

	private static HeldPriceMoveItemResponse sampleCard() {
		return new HeldPriceMoveItemResponse(
			12L,
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)),
			new BigDecimal("-0.018200"),
			115,
			195,
			"11시 20분부터 5분간 1.82% 하락했습니다.",
			List.of(new NewsItem(
				MarketNewsItemType.NEWS,
				"생산 차질",
				"hankyung.com",
				"https://news.example.test/1",
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));
	}
}
