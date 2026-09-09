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

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

class PostSellFeedbackServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 16, 0);

	private static final String LLM_NARRATIVE = "09시 30분 매수는 이날 하락 구간보다 1시간 55분 앞섰습니다.";
	private static final String TEMPLATE_NARRATIVE = "09시 30분에 70,000원에 매수해 14시 40분에 68,500원에 매도했습니다.";

	private static final RegenerationReasons GATE_REASON_ONLY = new RegenerationReasons(false, true);

	private static final RegenerationReasons JOURNAL_REASON_ONLY = new RegenerationReasons(true, false);

	private static final RegenerationReasons BOTH_REASONS = new RegenerationReasons(true, true);

	private static final String STORED_FINGERPRINT = "a".repeat(64);

	private static final String CURRENT_FINGERPRINT = "b".repeat(64);

	private static final String SELL_JOURNAL = "손절 라인을 지켰습니다.";

	private static final String BUY_JOURNAL = "실적 발표 전에 담았습니다.";

	private final PostSellFeedbackReader postSellFeedbackReader = mock(PostSellFeedbackReader.class);

	private final PostSellFeedbackContextReader postSellFeedbackContextReader = mock(
		PostSellFeedbackContextReader.class);

	private final PostSellJournalReader postSellJournalReader = mock(PostSellJournalReader.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final TradeFeedbackWriter tradeFeedbackWriter = mock(TradeFeedbackWriter.class);

	private final TradeFeedbackRepository tradeFeedbackRepository = mock(TradeFeedbackRepository.class);

	private static final FeedbackLlmProperties LLM_PROPERTIES = new FeedbackLlmProperties("gpt-5.4-mini", 20, 1024, 1,
		3, 3);

	private final PostSellFeedbackService postSellFeedbackService = new PostSellFeedbackService(
		postSellFeedbackReader, postSellFeedbackContextReader, postSellJournalReader, narrativeService,
		tradeFeedbackWriter, tradeFeedbackRepository, LLM_PROPERTIES, Clock.fixed(NOW.atZone(KST).toInstant(), KST));

	@BeforeEach
	void stubEmptyJournals() {
		when(postSellJournalReader.read(SELL_TRADE_ID)).thenReturn(JournalDigestDto.empty());
	}

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
		verify(tradeFeedbackWriter).create(
			eq(USER_ID), eq(SELL_TRADE_ID), eq(NarrativeResultDto.llm(LLM_NARRATIVE)), isNull(), eq(NOW));
	}

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

	@Test
	@DisplayName("같은 체결을 두 번 조회하면 LLM 호출이 정확히 1회이고 두 응답의 문장이 같다")
	void callsTheLlmExactlyOnceAcrossTwoQueriesOfTheSameTrade() {
		givenFacts(factsWithoutNarrative());
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
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

	@Test
	@DisplayName("중복이 아닌 무결성 위반으로 저장이 실패하고 행도 없으면 예외를 내보내지 않고 정상 응답을 준다")
	void stillReturnsTheResponseWhenANonDuplicateIntegrityViolationLeavesNoRow() {
		PostSellFeedbackResponse facts = factsWithoutNarrative();
		givenFacts(facts);
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Cannot add or update a child row: a foreign key constraint fails (`fk_trade_feedbacks_trade`)"));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.returnRate()).isEqualByComparingTo(facts.returnRate());
		assertThat(response.priceMoves()).isEqualTo(facts.priceMoves());
		verify(tradeFeedbackWriter).create(eq(USER_ID), eq(SELL_TRADE_ID), any(), isNull(), eq(NOW));
		verify(tradeFeedbackRepository, times(2)).findByTradeId(SELL_TRADE_ID);
	}

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
		assertThat(prompt.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		assertThat(prompt.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 40)));
		assertThat(prompt.multiDayHold()).isFalse();
		assertThat(prompt.buyPrice()).isEqualByComparingTo("70000");
		assertThat(prompt.sellPrice()).isEqualByComparingTo("68500");
		assertThat(prompt.realizedPnl()).isEqualTo(-15_207L);
		assertThat(prompt.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(prompt.buyToNewsMinutes()).isEqualTo(105);
		assertThat(prompt.firstNewsAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		assertThat(prompt.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.windowEnd()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
			assertThat(move.minutesAfterBuy()).isEqualTo(115);
			assertThat(move.sources()).singleElement()
				.satisfies(source -> assertThat(source.disclosure()).isFalse());
		});
		assertThat(prompt.closePrice()).isEqualByComparingTo("69200");
		assertThat(prompt.sellToCloseRate()).isEqualByComparingTo("0.0102");
		assertThat(prompt.holderCount()).isNull();
		assertThat(prompt.soldWithin30MinRate()).isNull();
		assertThat(prompt.medianMinutesToSell()).isNull();
		assertThat(prompt.yourMinutesToSell()).isNull();
	}

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
		assertThat(prompt.holdHighBasis()).isEqualTo(HoldHighBasis.DAILY);
	}

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

	@Test
	@DisplayName("매도 후 흐름이 NOT_YET이면 집단 비교가 확정이어도 재생성하지 않는다")
	void keepsTheGateClosedWhilePostSellFlowIsNotYet() {
		givenFacts(factsWithoutNarrative(
			true, PostSellFeedbackStatus.NOT_YET, PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	@Test
	@DisplayName("재생성이 템플릿으로 폴백하면 기존 서술을 유지하고 실패만 누적한다")
	void keepsTheStoredNarrativeWhenRegenerationFallsBackToTheTemplate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

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

	@Test
	@DisplayName("누적 시도가 상한에 도달하면 LLM을 부르지 않는다")
	void stopsRetryingWhenTheCumulativeLimitIsReached() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry()));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	@Test
	@DisplayName("누적 시도가 상한을 넘었으면 게이트 계산 전에 멈춘다")
	void checksTheCumulativeLimitBeforeTheGate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry() + 1));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

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

	@Test
	@DisplayName("확정된 서술도 일기 지문이 다르면 재생성한다 — 일기 판정은 narrative_finalized를 보지 않는다")
	void regeneratesForTheJournalReasonEvenWhenTheNarrativeIsAlreadyFinalized() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(finalizedFeedback(STORED_FINGERPRINT));
		givenJournals(journalsWith(CURRENT_FINGERPRINT));
		givenGenerated(NarrativeResultDto.llm("일기를 반영한 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("일기를 반영한 문장입니다.");
		verify(tradeFeedbackWriter).applyRegenerated(
			eq(SELL_TRADE_ID), any(), eq(CURRENT_FINGERPRINT), eq(JOURNAL_REASON_ONLY), eq(NOW));
		verify(narrativeService, times(1)).resolvePostSellNarrative(any());
	}

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

	@Test
	@DisplayName("커뮤니티 매매 카드 요약은 loadContext의 원장 값만 조립하고 reader.read()를 부르지 않는다")
	void getTradeShareSummaryMapsFactsFromLoadContextWithoutCallingReader() {
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

	private static TradeFeedback journalConsumedFeedback(int consumed, String fingerprint) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, fingerprint);
		for (int i = 0; i < consumed; i++) {
			feedback.countJournalRegeneration();
		}
		return feedback;
	}

	private static TradeFeedback finalizedFeedback() {
		return finalizedFeedback(null);
	}

	private static TradeFeedback finalizedFeedback(String fingerprint) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM, fingerprint);
		feedback.applyRegeneratedNarrative("확정된 문장입니다.", NarrativeSource.LLM, fingerprint, NOW.minusMinutes(10));
		return feedback;
	}

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

	private static PostSellFeedbackResponse gateOpenFacts(PostSellFeedbackStatus peerStatus) {
		return factsWithoutNarrative(true, PostSellFeedbackStatus.READY, peerStatus);
	}

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

	private static HeldPriceMoveItem sampleCard() {
		return new HeldPriceMoveItem(
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
