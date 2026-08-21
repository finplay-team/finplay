// 매도 직후 피드백 조회의 진입점 — 읽기·LLM 호출·저장을 서로 다른 트랜잭션 경계로 갈라 순서대로 엮는다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItemResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 계약은 {@code docs/api/feedback.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 * 원장 수치·파생 사실 조립은 {@link PostSellFeedbackReader}가, 저장은 {@link TradeFeedbackWriter}가 한다.
 *
 * <p><b>이 클래스에 {@code @Transactional}이 없는 것이 설계다.</b> spec 012에서 <b>조회 경로에 LLM이 들어오는
 * 첫 자리</b>이고({@code docs/conventions/code.md}의 "GET은 부수효과 없음"에 대한 이 spec의 유일한 예외, 체결 1건당
 * 1회) LLM 호출이 중앙값 2.5초·p95 3.1초다(#198 실측). 여기에 트랜잭션을 걸면 <b>사용자가 기다리는 그 3초 동안
 * DB 커넥션을 쥐고</b> 있어 커넥션 풀 크기가 곧 동시 조회 수의 상한이 된다. 트랜잭션은 메서드 단위이므로
 * 경계를 좁히는 방법은 협력자를 나누는 것뿐이다 — <b>같은 클래스의 private 메서드에 애노테이션을 붙이면
 * 자기호출이라 프록시를 타지 않아 무효이고, 정확히 막으려던 상태가 조용히 된다.</b>
 *
 * <pre>
 * 1. reader.read(...)           트랜잭션 없음(오케스트레이터)     — 협력자별 트랜잭션으로 원장·분봉·카드를 읽는다
 * 2. journalReader.read(...)    트랜잭션 없음(오케스트레이터)     — journal·portfolio 서비스가 각자 읽기 트랜잭션
 * 3. findByTradeId(...)         리포지터리 기본 트랜잭션          — 기존 서술이 있으면 4단계를 건너뛸 수 있다
 * 4. narrativeService.resolve   트랜잭션 없음                     — 외부 LLM 호출이 여기 있다
 * 5. writer.create(...)         @Transactional                   — 저장만 감싼다
 * </pre>
 *
 * <p><b>서술은 최초 조회에서 만들어 저장하고 이후 재사용한다</b>(FEED-007, {@code UNIQUE(trade_id)}).
 * <b>재생성 사유는 둘이고 카운터도 둘이다</b>(§FEED-013 결정 3, 4차).
 *
 * <pre>
 * 1. 기존 행 없음                 → 생성 후 저장 (일기가 있으면 프롬프트에 함께 실린다)
 * 2. 일기 지문이 저장된 값과 다름 → 일기 사유      (journal_regenerations &lt; max-journal-regeneration)
 * 3. §C-5 게이트 통과 + 미확정    → 흐름·집단 사유 (regeneration_attempts  &lt; max-narrative-retry)
 * 4. 그 밖                        → 저장된 서술 재사용
 * </pre>
 *
 * <p>흐름·집단 사유는 매도 후 흐름과 집단 비교가 확정된 뒤 첫 조회에서 갈아 끼우고
 * ({@link #isRegenerationGateOpen}) 성공하면 {@code narrative_finalized=TRUE}로 닫힌다. <b>일기 사유는 시각
 * 게이트가 아니라 값의 대조라 일기를 고칠 때마다 다시 열리며, {@code narrative_finalized}를 보지도 쓰지도
 * 않는다</b> — 보면 게이트를 이미 통과한 체결에서 일기가 영원히 반영되지 않고, 쓰면 흐름·집단 게이트가 조기에
 * 닫힌다. 어느 쪽이든 템플릿으로 폴백하면 기존 서술과 지문을 유지한 채 해당 카운터만 누적해 상한까지 다시
 * 시도한다. <b>두 사유가 동시에 성립해도 LLM은 한 번만 부르고 카운터는 둘 다 오른다.</b> 그 밖에는 매도 체결이
 * 불변 원장이므로 재생성하지 않는다.
 *
 * <p><b>{@code narrativeStatus}는 항상 {@code READY}다</b>(§C-4) — 매도 회고에는 §템플릿 문장이 있어 LLM이
 * 실패하거나 후검증에 걸려도 서버가 수치로 조립한 문장으로 대체하므로 서술이 비지 않는다. 어느 쪽으로
 * 만들어졌는지는 {@code narrativeSource}({@code LLM}|{@code TEMPLATE})로 구분하며 이 엔드포인트에
 * <b>{@code UNAVAILABLE}이 존재하지 않는다.</b> 그 보장을 실제로 지키는 것은 {@code NarrativeService}의 1단계
 * 폴백 경로이므로 여기서 상태값을 분기하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostSellFeedbackService {

	private final PostSellFeedbackReader postSellFeedbackReader;

	private final PostSellFeedbackContextReader postSellFeedbackContextReader;

	private final PostSellJournalReader postSellJournalReader;

	private final NarrativeService narrativeService;

	private final TradeFeedbackWriter tradeFeedbackWriter;

	private final TradeFeedbackRepository tradeFeedbackRepository;

	private final FeedbackLlmProperties feedbackLlmProperties;

	private final Clock clock;

	/**
	 * 본인 매도 체결 1건의 회고를 조회한다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수 체결은 400
	 *     {@code VALIDATION_ERROR}다 — 판정은 {@code reader}가 하며 <b>서술 생성보다 먼저</b>다. <b>코인 체결은
	 *     3차부터 200이고</b>(이슈 #275) 시장별 조립도 {@code reader}가 가른다 — 이 클래스는 시장을 모른다
	 */
	public PostSellFeedbackResponse getPostSellFeedback(Long userId, Long tradeId) {
		PostSellFeedbackResponse facts = postSellFeedbackReader.read(userId, tradeId);
		// 기존 행 유무와 무관하게 조회마다 한 번 읽는다 — 최초 생성에도 저장할 지문이 필요하고, 재사용
		// 판정에도 현재 지문이 필요하다. DB 읽기라 트랜잭션 경계 규칙(LLM은 경계 밖)을 그대로 지킨다.
		JournalDigestDto journals = postSellJournalReader.read(tradeId);
		NarrativeResultDto narrative = resolveNarrative(userId, tradeId, facts, journals);
		// narrativeStatus는 상수 READY다 — 위 클래스 주석의 근거이며 분기가 없는 것이 의도다.
		return facts.withNarrative(
			narrative.narrative(), narrative.source(), PostSellFeedbackStatus.READY);
	}

	/**
	 * 커뮤니티 매매 카드 공유용 가벼운 요약이다(spec 046 TRADESHARE-002·003). <b>{@code postSellFeedbackReader.read()}를
	 * 부르지 않는다</b> — 그 경로는 가격 변동 카드·뉴스·반사실·집단 비교를 전부 계산하고 코인이면 그 안에서
	 * 빗썸 REST를 최대 4회(최악 ~20초) 부르는 무거운 경로이고, 결국 쓰는 값은 8개뿐이다. 이 메서드는
	 * {@link CommunityPostService}의 {@code @Transactional} 메서드에서 호출되므로, 그 REST 호출을 트랜잭션
	 * 안에 가두면 DB 커넥션을 수십 초씩 쥐는 문제가 재현된다(이슈 #282가 코인 리더를 무트랜잭션으로 뺀 이유와
	 * 정확히 같다) — 그래서 {@link PostSellFeedbackContextReader#loadContext}(순수 DB 조회, 존재·소유·매수
	 * 체결 검증까지 이미 함) <b>하나만</b> 타고 나머지는 여기서 직접 조립한다.
	 *
	 * <p>{@code buyPrice}는 {@code allocation.buyPrice()}(FIFO 배분 가중평균, 재계산 아님), {@code sellPrice}·
	 * {@code quantity}·{@code realizedPnl}은 {@code trades} 행 그대로, {@code returnRate}는
	 * {@link PostSellArithmetic#returnRate}로 매도 직후 피드백과 <b>같은 식</b>을 재사용한다(PRD C-004).
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수 체결은 400
	 *     {@code VALIDATION_ERROR}다 — 판정은 {@code loadContext}가 한다({@link #getPostSellFeedback}과 동일 순서)
	 */
	public TradeShareSummaryResponse getTradeShareSummary(Long userId, Long tradeId) {
		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(userId, tradeId);
		Trade trade = context.trade();
		SellAllocationSummaryDto allocation = context.allocation();
		Instrument instrument = trade.getInstrument();
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();
		BigDecimal returnRate = PostSellArithmetic.returnRate(trade.getRealizedPnl(), buyBasis);
		return new TradeShareSummaryResponse(
			instrument.getSymbol(),
			instrument.getName(),
			instrument.getMarket(),
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getRealizedPnl(),
			returnRate);
	}

	/**
	 * 기존 서술이 없으면 만들어 저장하고, 있으면 재사용한다 — <b>유일한 예외가 재생성 1회</b>다(§C-5).
	 *
	 * <p><b>기존 행 조회를 빠뜨리면 조회마다 LLM을 다시 부른다</b> — 호출량이 조회 수에 비례하고 같은 체결의
	 * 문장이 매번 달라지는데 예외도 로그도 없다. 체결은 불변 원장이라 수치가 바뀌지 않으므로 재사용이 정확한
	 * 동작이다.
	 *
	 * <p><b>LLM 실패가 응답을 막지 않는다.</b> 실패·타임아웃·OpenAI 키 없음은 {@code NarrativeService}가 템플릿
	 * 문장으로 흡수하므로(§실패 처리) 여기에 예외 처리가 없는 것이 정상이다 — 수치 요약과 파생 사실은 그대로
	 * 200으로 나간다. 반대로 여기서 {@code try/catch}로 서술을 삼키면 {@code narrative}가 {@code null}인 응답이
	 * 나가면서 "{@code narrativeStatus}는 항상 {@code READY}"가 조용히 깨진다.
	 */
	private NarrativeResultDto resolveNarrative(
		Long userId, Long tradeId, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		Optional<TradeFeedback> found = tradeFeedbackRepository.findByTradeId(tradeId);
		if (found.isEmpty()) {
			return createNarrative(userId, tradeId, facts, journals);
		}

		TradeFeedback existing = found.get();
		NarrativeResultDto stored = new NarrativeResultDto(existing.getNarrative(), existing.getNarrativeSource());
		RegenerationReasons reasons = regenerationReasons(tradeId, existing, facts, journals);
		return reasons.any() ? regenerateNarrative(tradeId, facts, journals, stored, reasons) : stored;
	}

	/**
	 * 최초 조회 — 생성해 저장한다 ({@code UNIQUE(trade_id)}가 체결 1건당 1행을 강제한다).
	 *
	 * <p><b>이번 프롬프트에 실린 일기의 지문을 함께 저장한다</b>(§FEED-013 결정 3). 빠뜨리면 저장된 지문이 늘
	 * {@code null}이라 <b>일기가 있는 체결의 모든 조회가 "지문 다름"으로 판정돼 조회마다 LLM을 부른다</b> —
	 * 상한에 닿기 전까지 그렇고, 응답은 정상 200이라 신호가 없다.
	 */
	private NarrativeResultDto createNarrative(
		Long userId, Long tradeId, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		NarrativeResultDto resolved = narrativeService.resolvePostSellNarrative(toPromptInput(facts, journals));
		try {
			tradeFeedbackWriter.create(userId, tradeId, resolved, journals.fingerprint(), LocalDateTime.now(clock));
		} catch (DataIntegrityViolationException e) {
			absorbOnlyDuplicateRow(tradeId, e);
		}
		return resolved;
	}

	/**
	 * {@code UNIQUE(trade_id)} 충돌만 삼키고, <b>그 밖의 무결성 위반은 {@code WARN}으로 남긴다.</b>
	 *
	 * <p>삼켜도 되는 경우는 하나다 — 같은 체결을 동시에 두 번 조회하면(화면 이중 요청, 새로고침 연타) 둘 다
	 * "기존 행 없음"을 보고 각자 생성한다. 먼저 커밋한 쪽의 행을 그대로 두고 이번 응답은 방금 만든 문장으로
	 * 내린다 — 두 문장 모두 같은 수치에서 나온 관찰형 서술이라 사용자가 보는 내용이 어긋나지 않고, 여기서
	 * 500을 내면 조회가 실패한다(§실패 처리의 "UNIQUE 제약으로 무시, 기존 데이터 유지").
	 *
	 * <p><b>판정을 예외 타입이 아니라 "행이 실제로 있는가"로 한다.</b> {@code DuplicateKeyException}으로 좁히는
	 * 방법도 있지만 그 매핑에 기댈 수 없다 — Hibernate가 던지는 {@code ConstraintViolationException}은
	 * {@code HibernateJpaDialect}가 <b>기반 타입인 {@code DataIntegrityViolationException}으로</b> 번역하고,
	 * {@code DuplicateKeyException}은 JDBC 에러코드 번역 경로에서 붙는 하위 타입이다. 좁혔다가 매핑이 예상과
	 * 다르면 <b>정상 경합이 500이 된다</b>. 이 환경에서는 MySQL 없이 실제 번역 타입을 확인할 수 없으므로
	 * 기억으로 타입을 고르지 않는다({@code ai/agent-mistakes.md}의 반복 패턴이다).
	 *
	 * <p>행 재조회는 <b>번역 타입과 무관하게 성립하고</b> 원래 막으려던 상태를 정확히 가른다. FK·NOT NULL 위반은
	 * 행이 안 생기므로 {@code WARN}으로 드러난다 — 조용히 넘기면 그 체결은 <b>조회마다 LLM을 다시 부르는데</b>
	 * 응답이 정상 200이라 아무 신호도 남지 않는다.
	 */
	private void absorbOnlyDuplicateRow(Long tradeId, DataIntegrityViolationException e) {
		if (tradeFeedbackRepository.findByTradeId(tradeId).isPresent()) {
			log.debug("매도 회고 서술이 이미 저장돼 있어 이번 저장은 건너뛴다. tradeId={}", tradeId);
			return;
		}
		log.warn(
			"매도 회고 서술 저장이 무결성 위반으로 실패했고 행도 없다. 이 체결은 조회마다 서술을 다시 만든다. tradeId={}",
			tradeId,
			e);
	}

	/**
	 * 두 사유를 각각 판정한다 (§FEED-013 결정 3). <b>둘 다 참일 수 있고, 그때도 LLM은 한 번만 부른다</b> —
	 * 프롬프트에 매도 후 흐름·집단 비교·일기가 모두 실리므로 한 번의 생성이 두 사유를 함께 반영한다.
	 *
	 * <p><b>두 판정이 서로의 상태를 읽지 않는 것이 요점이다.</b> 일기 사유는 지문과 {@code journalRegenerations}만
	 * 보고, 흐름·집단 사유는 {@code narrativeFinalized}와 {@code regenerationAttempts}만 본다.
	 */
	private RegenerationReasons regenerationReasons(
		Long tradeId, TradeFeedback existing, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		return new RegenerationReasons(
			shouldRegenerateForJournal(tradeId, existing, journals), shouldRegenerate(existing, facts));
	}

	/**
	 * 투자일기 사유 — <b>저장된 지문 ≠ 현재 지문</b>이고 {@code journal_regenerations}가
	 * {@code max-journal-regeneration} 미만이다 (§FEED-013 결정 3, 4차).
	 *
	 * <p><b>{@code narrativeFinalized}를 보지 않는다.</b> 그 플래그는 흐름·집단 게이트 전용이라 여기서 읽으면
	 * <b>게이트를 이미 통과한 체결에서 일기가 영원히 반영되지 않는다</b> — 예외도 로그도 없이 그렇게 된다. 이
	 * 이슈에서 가장 조용히 틀리는 자리다.
	 *
	 * <p>지문 비교는 {@link Objects#equals}로 한다. 일기가 없으면 지문이 {@code null}이고 <b>{@code null}에서 값으로
	 * 바뀌는 것도 "달라짐"</b>이라(결정 3) 양쪽 {@code null}을 함께 다뤄야 한다 — 그 경로가 "피드백을 먼저 보고
	 * 나중에 회고를 쓰는" 결정 1의 사용자다.
	 *
	 * <p><b>지문이 다른데 상한을 넘겼으면 그냥 재사용한다 — 오류가 아니다</b>(결정 3). 사용자는 일기를 계속 고칠
	 * 수 있고 서술이 그만큼 다시 만들어질 이유는 없다.
	 */
	private boolean shouldRegenerateForJournal(
		Long tradeId, TradeFeedback existing, JournalDigestDto journals) {
		if (Objects.equals(existing.getJournalFingerprint(), journals.fingerprint())) {
			return false;
		}
		if (existing.getJournalRegenerations() >= feedbackLlmProperties.maxJournalRegeneration()) {
			log.debug(
				"투자일기가 바뀌었지만 재생성 상한에 닿아 기존 서술을 재사용한다. tradeId={} journalRegenerations={}",
				tradeId,
				existing.getJournalRegenerations());
			return false;
		}
		return true;
	}

	/**
	 * 흐름·집단 사유 — <b>확정 전 + 누적 상한 안 + §C-5의 재생성 게이트 통과</b> 셋을 모두 만족해야 한다.
	 *
	 * <p>순서에 이유가 있다. {@code narrativeFinalized}와 상한은 <b>DB 값만 보는 판정</b>이라 먼저 걸러야
	 * 게이트 계산이 헛돌지 않고, 무엇보다 상한을 게이트보다 뒤에 두면 상한을 넘긴 체결이 게이트가 열린 동안
	 * 계속 LLM을 부른다.
	 *
	 * <p><b>누적 상한은 {@code max-narrative-retry}이고 날짜로 리셋하지 않는다</b>(FEED-007·§C-7). 실패 시
	 * {@code generatedAt}을 갱신하지 않으므로 날짜 기준 자체가 성립하지 않으며, {@code regeneration_attempts}가
	 * 체결 1건당 누적으로 오른다. <b>상한 이하만 재현하는 테스트는 리셋 버그를 잡지 못한다</b> — 실패를 상한 + 1회
	 * 재현해 마지막 호출이 실제로 일어나지 않는지 봐야 한다.
	 */
	private boolean shouldRegenerate(TradeFeedback existing, PostSellFeedbackResponse facts) {
		if (existing.isNarrativeFinalized()) {
			return false;
		}
		if (existing.getRegenerationAttempts() >= feedbackLlmProperties.maxNarrativeRetry()) {
			return false;
		}
		return isRegenerationGateOpen(facts);
	}

	/**
	 * §C-5의 재생성 게이트 — {@code postSellFlow}가 {@code READY}이고 <b>{@code peerComparison.status}가
	 * {@code NOT_YET}이 아니다.</b>
	 *
	 * <p><b>{@code NO_EVENT}·{@code INSUFFICIENT_SAMPLE}도 확정으로 친다.</b> 게이트를 "확정 집계 행이 있다"로
	 * 두면 <b>보유 구간 카드가 0건인 체결에서 매도 후 흐름이 반영된 서술이 영원히 만들어지지 않는다</b> — 카드가
	 * 없으면 {@code price_move_peer_stats} 행이 애초에 생기지 않고, 카드는 종목·거래일당
	 * {@code max-intraday-cards}건에 근거 기사가 없으면 생성되지 않으므로 <b>0건이 오히려 흔한 경우다.</b>
	 * 그 상태는 예외도 로그도 없이 일어난다. 그래서 판정을 행 존재가 아니라 <b>상태값</b>으로 둔다.
	 *
	 * <p><b>매도 후 흐름만 보고 열지 않는다.</b> 그러면 15:30~장 마감 집계 사이에 조회한 사용자는 집단 비교가
	 * 빠진 문장으로 굳는다 — 재생성이 1회뿐이라 되돌릴 기회가 없다(계약).
	 *
	 * <p>이슈 #212 4번이 {@code peerComparison.status}에 확정 집계 행 기준 실제 판정을 붙였다
	 * ({@code PostSellFeedbackReader.buildPeerComparison}) — 이 게이트 조건은 그 순간 아무 수정 없이 열렸다.
	 *
	 * <p>{@code sameSessionCompleted=false}면 두 필드가 모두 {@code null}이라 자연히 닫힌다 — 여러 재생일에 걸친
	 * 매매에는 반영할 매도 후 흐름이 애초에 없다.
	 */
	private static boolean isRegenerationGateOpen(PostSellFeedbackResponse facts) {
		PostSellFlow flow = facts.postSellFlow();
		PeerComparison peer = facts.peerComparison();
		return flow != null
			&& flow.status() == PostSellFeedbackStatus.READY
			&& peer != null
			&& peer.status() != PostSellFeedbackStatus.NOT_YET;
	}

	/**
	 * 게이트를 통과한 뒤 <b>1회</b> 갈아 끼운다. 재생성도 <b>1단계 경로</b>다 — 생성 → 후검증 → 걸리면 템플릿이며
	 * §후검증의 요약·브리핑용 2단계(적발 시 재생성)와 다른 것이다.
	 *
	 * <p>프롬프트 입력이 최초 생성과 <b>같은 매핑</b>인 것이 요점이다. 게이트가 열렸으므로 이번에는
	 * {@code closePrice}·{@code sellToCloseRate}와 집단 비교 지표가 채워져 프롬프트에 줄이 붙는다 — 그래서
	 * 다른 문장이 나온다. <b>매핑을 따로 만들면 그 줄이 빠진 프롬프트로 재생성해 게이트가 무의미해진다.</b>
	 *
	 * <p><b>템플릿으로 폴백했으면 실패로 취급한다.</b> 템플릿 문장에는 매도 후 흐름·집단 비교가 없으므로 기존
	 * 문장을 그것으로 덮으면 재생성할수록 서술이 빈약해진다. 기존 서술을 유지하고 {@code narrative_finalized}를
	 * {@code false}로 남겨 다음 조회에서 상한 안이면 다시 시도한다.
	 *
	 * <p><b>사유가 둘이어도 생성기는 한 번만 부른다</b>(§FEED-013 결정 3). 두 번 부르면 비용이 두 배가 되는데
	 * 두 번째 프롬프트는 첫 번째와 같은 재료라 다른 문장이 나올 이유도 없다. 사유별 카운터 반영은 저장 쪽
	 * ({@link TradeFeedbackWriter})이 {@code reasons}를 보고 가른다.
	 *
	 * <p><b>템플릿 폴백에서는 지문도 유지한다</b> — 그 서술에는 일기가 반영되지 않았는데 지문만 맞춰 두면 다음
	 * 조회가 "이미 반영됐다"고 판정해 일기가 영원히 반영되지 않는다.
	 *
	 * @param stored 실패 시 그대로 응답에 실리는 기존 서술
	 */
	private NarrativeResultDto regenerateNarrative(
		Long tradeId,
		PostSellFeedbackResponse facts,
		JournalDigestDto journals,
		NarrativeResultDto stored,
		RegenerationReasons reasons) {
		NarrativeResultDto resolved = narrativeService.resolvePostSellNarrative(toPromptInput(facts, journals));
		if (resolved.source() != NarrativeSource.LLM) {
			log.debug(
				"매도 회고 서술 재생성이 템플릿으로 폴백해 기존 서술을 유지한다. tradeId={} reasons={}", tradeId, reasons);
			tradeFeedbackWriter.recordFailedRegeneration(tradeId, reasons);
			return stored;
		}
		tradeFeedbackWriter.applyRegenerated(
			tradeId, resolved, journals.fingerprint(), reasons, LocalDateTime.now(clock));
		return resolved;
	}

	/**
	 * 응답 조립 결과를 프롬프트 입력으로 옮긴다 (§LLM 프롬프트).
	 *
	 * <p><b>반사실({@code counterfactuals})을 넣지 않는다.</b> {@code PostSellPromptDto}에 그 필드가 애초에
	 * 없으며 이유는 §왜 반사실은 AI 문장에 넣지 않는가에 있다 — {@code "안 팔았다면 -1.17%였습니다"}는 사실이지만
	 * "팔지 말걸"을 암시해 §후검증의 가정법 금지와 정면으로 부딪힌다. <b>필드를 추가하고 싶으면 spec을 먼저
	 * 고친다.</b>
	 *
	 * <p>반대로 <b>집단 비교는 관측된 사실이라 서술에 넣어도 된다</b>(FEED-011)  자리가 nullable로 열려 있다.
	 * {@code peerComparison}이 {@code NOT_YET}·{@code NO_EVENT}면 지표가 전부 {@code null}이므로 넘길 값이 없는
	 * 것이 정상 상태고, {@code READY}·{@code INSUFFICIENT_SAMPLE}이면 이슈 #212 4번이 채운 값이 같은 매핑으로
	 * 흘러 들어간다.
	 *
	 * <p><b>시각은 날짜까지 넘긴다</b>(이슈 #275). 원래는 프롬프트가 {@code HH:mm}만 쓴다는 이유로
	 * {@code LocalTime}으로 좁혔는데, 코인은 보유가 며칠에 걸치는 경우가 흔해 그렇게 하면 <b>매도가 매수보다
	 * 이른 문장</b>이 나온다. 날짜를 실제로 문장에 쓸지는 {@code multiDayHold}가 정하고, 주식은 그 값이 언제나
	 * 거짓이라 문장이 달라지지 않는다.
	 */
	private static PostSellPromptDto toPromptInput(PostSellFeedbackResponse facts, JournalDigestDto journals) {
		PostSellFlow flow = facts.postSellFlow();
		PeerComparison peer = facts.peerComparison();
		return new PostSellPromptDto(
			facts.name(),
			facts.buyAt(),
			facts.buyPrice(),
			facts.sellAt(),
			facts.sellPrice(),
			facts.quantity(),
			facts.returnRate(),
			facts.realizedPnl() == null ? 0L : facts.realizedPnl(),
			facts.holdHighPrice(),
			facts.holdHighAt(),
			facts.sellVsHighRate(),
			facts.holdLowPrice(),
			facts.holdLowAt(),
			facts.sellVsLowRate(),
			facts.buyToNewsMinutes(),
			// buyToNewsMinutes가 있으면 firstNewsAt도 있어야 한다 — 둘이 같은 근거 기사 하나에서 나오므로
			// 한쪽만 채우면 프롬프트에 "몇 분 앞섰다"만 남고 그 기준 시각이 사라진다(PostSellPromptDto 주석).
			firstNewsAt(facts.priceMoves()),
			toPromptPriceMoves(facts.priceMoves()),
			flow == null ? null : flow.closePrice(),
			flow == null ? null : flow.sellToCloseRate(),
			peer == null ? null : peer.holderCount(),
			peer == null ? null : peer.soldWithin30MinRate(),
			peer == null ? null : peer.medianMinutesToSell(),
			peer == null ? null : peer.yourMinutesToSell(),
			// 주식은 sameSessionCompleted=true가 곧 "같은 원본 거래일"이라 이 값이 언제나 거짓이다 — 그래서 이
			// 판정이 붙어도 주식 문장은 그대로다. 여러 거래일에 걸친 주식 매매(false)는 극값 자체가 null이고
			// 날짜를 서술할 근거도 없어 기존 동작을 유지한다.
			facts.sameSessionCompleted() && !facts.buyAt().toLocalDate().equals(facts.sellAt().toLocalDate()),
			facts.holdHighBasis(),
			// 투자일기는 응답(facts)에 실리지 않으므로 PostSellJournalReader가 읽은 것을 따로 받는다 —
			// 일기 본문은 응답 필드가 아니다(§범위 제외. 프론트는 GET /api/journal/...로 읽는다).
			// 고르기·정렬·절단은 그 리더가 이미 끝냈고 여기서는 프롬프트에 쓰는 두 값만 옮긴다.
			// 지문은 옮기지 않는다 — 프롬프트에 쓰지 않는 값이라 문자열 단정이 무관한 값에 흔들린다.
			toPromptJournals(journals),
			journals.sellJournalContent());
	}

	// 매수 회고 줄 — 순서(매수 시각 오름차순)와 상한·절단은 PostSellJournalReader가 정한 그대로 따른다.
	private static List<BuyJournalLineDto> toPromptJournals(JournalDigestDto journals) {
		return journals.buyJournals()
			.stream()
			.map(journal -> new BuyJournalLineDto(journal.buyAt(), journal.content()))
			.toList();
	}

	/** 보유 구간 카드의 근거 기사 중 가장 이른 발행시각 — {@code buyToNewsMinutes}의 기준값 {@code T0}다. */
	private static LocalDateTime firstNewsAt(List<HeldPriceMoveItemResponse> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.orElse(null);
	}

	// 응답 항목과 프롬프트 항목을 따로 두는 것은 #147의 결정이다 — 프롬프트에는 원문 URL을 주지 않고(모델이
	// 인용하려 들 뿐 서술에 쓸모가 없다) 공시 여부는 열거형 대신 boolean으로 받는다.
	private static List<HeldPriceMoveDto> toPromptPriceMoves(List<HeldPriceMoveItemResponse> priceMoves) {
		return priceMoves.stream()
			.map(move -> new HeldPriceMoveDto(
				move.windowStart(),
				move.windowEnd(),
				move.changeRate(),
				move.minutesAfterBuy(),
				move.minutesBeforeSell(),
				move.sources().stream()
					.map(source -> new NewsSourceDto(
						source.title(),
						source.publisher(),
						source.publishedAt(),
						source.type() == MarketNewsItemType.DISCLOSURE))
					.toList()))
			.toList();
	}
}
