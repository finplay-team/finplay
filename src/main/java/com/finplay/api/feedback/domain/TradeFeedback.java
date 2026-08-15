// 매도 체결 1건에 대한 회고 서술을 영속하는 엔티티 — 이 spec에서 유일하게 회원별로 생기는 산출물이다.
package com.finplay.api.feedback.domain;

import com.finplay.api.order.domain.Trade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카드·요약·브리핑은 전 회원이 공유하지만 이 서술만 체결 1건당 1행이라 유니크가 {@code (trade_id)}다
 * (§데이터 모델). 최초 조회 시 생성해 저장하고 이후에는 재사용한다 (FEED-007).
 *
 * <p><b>{@code trade}는 읽기만 하는 참조다.</b> {@code trades}는 {@code order} 소유의 원장이고 이 spec의 어떤
 * 코드도 원장을 바꾸지 않는다 — 그래서 연관에 cascade를 걸지 않았다.
 *
 * <p>{@code narrativeFinalized}는 <b>§C-5의 재생성 게이트를 통과한 서술인지</b>이며 "장 마감이 지났는지"가
 * 아니다 — 집단 비교가 확정(`NO_EVENT`·`INSUFFICIENT_SAMPLE` 포함)돼야 {@code true}가 된다.
 * {@code regenerationAttempts}는 <b>체결 1건당 누적</b> 재시도 횟수이며 날짜로 리셋하지 않는다 — 재생성이
 * 실패하면 {@code generatedAt}을 갱신하지 않아 날짜 기준 자체가 성립하지 않는다 (§데이터 모델·§C-8).
 *
 * <p><b>재생성 게이트 판정은 조회 서비스 소유다</b>({@code PostSellFeedbackService}) — 이 엔티티는 판정 결과에
 * 따른 <b>전이</b>만 갖는다. 게이트는 응답의 {@code postSellFlow}·{@code peerComparison} 상태를 보는데, 그 둘은
 * 저장 컬럼이 아니라 조회 시 계산하는 값이라 엔티티가 알 수 있는 정보가 아니다.
 *
 * <p>{@code journalFingerprint}는 <b>이 서술을 만들 때 프롬프트에 실린 투자일기의 지문</b>이다 (SHA-256 hex 64자,
 * §FEED-013 결정 3). {@code null}은 "그때 일기가 없었다"는 뜻이며 유의미한 상태다 — <b>{@code null}에서 값으로
 * 바뀌는 것도 "달라짐"</b>이라 일기를 나중에 쓴 체결에서 재생성이 열린다.
 *
 * <p>{@code journalRegenerations}는 <b>일기 사유 재생성 누적 횟수</b>이며 {@code regenerationAttempts}와 따로
 * 센다 — 합치면 일기를 여러 번 고친 체결이 흐름·집단 반영 기회를 잃는다. 같은 이유로 <b>일기 판정에는
 * {@code narrativeFinalized}를 쓰지 않는다</b> — 쓰면 게이트를 이미 통과한 체결에서 일기가 영원히 반영되지
 * 않는데 예외도 로그도 남지 않는다 (§FEED-013 결정 3).
 */
@Entity
@Table(name = "trade_feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeFeedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "trade_id", nullable = false)
	private Trade trade;

	@Column(columnDefinition = "TEXT")
	private String narrative;

	@Enumerated(EnumType.STRING)
	@Column(name = "narrative_source", nullable = false, length = 20)
	private NarrativeSource narrativeSource;

	@Column(name = "narrative_finalized", nullable = false)
	private boolean narrativeFinalized;

	@Column(name = "regeneration_attempts", nullable = false)
	private int regenerationAttempts;

	@Column(name = "journal_fingerprint", length = 64)
	private String journalFingerprint;

	@Column(name = "journal_regenerations", nullable = false)
	private int journalRegenerations;

	@Column(name = "generated_at", nullable = false)
	private LocalDateTime generatedAt;

	private TradeFeedback(
		Trade trade,
		String narrative,
		NarrativeSource narrativeSource,
		boolean narrativeFinalized,
		int regenerationAttempts,
		String journalFingerprint,
		int journalRegenerations,
		LocalDateTime generatedAt) {
		this.trade = trade;
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.narrativeFinalized = narrativeFinalized;
		this.regenerationAttempts = regenerationAttempts;
		this.journalFingerprint = journalFingerprint;
		this.journalRegenerations = journalRegenerations;
		this.generatedAt = generatedAt;
	}

	/**
	 * 최초 생성이라 {@code narrativeFinalized = false}·{@code regenerationAttempts = 0}·
	 * {@code journalRegenerations = 0}이다 (§C-8의 기본값). 재생성 게이트를 통과한 서술로 바꾸는 것은 조회 서비스
	 * 책임이다.
	 *
	 * @param narrativeSource 매도 회고는 템플릿이 있어 {@code LLM} 아니면 {@code TEMPLATE}이다. {@code NONE}이
	 *                        되지 않으므로 {@code narrative}가 비지 않는다 (§C-4)
	 * @param journalFingerprint <b>최초 생성에서도 반드시 채운다</b> — 이 프롬프트에 실린 일기의 지문이며, 일기가
	 *     없었으면 {@code null}이다. 저장하지 않으면 다음 조회부터 <b>모든 조회가 "지문 다름"으로 판정돼 조회마다
	 *     LLM을 부른다</b>. 응답은 정상 200이라 아무 신호도 남지 않는다 (§FEED-013 결정 3)
	 */
	public static TradeFeedback create(
		Trade trade,
		String narrative,
		NarrativeSource narrativeSource,
		String journalFingerprint,
		LocalDateTime generatedAt) {
		return new TradeFeedback(trade, narrative, narrativeSource, false, 0, journalFingerprint, 0, generatedAt);
	}

	/**
	 * 재생성 <b>성공</b> — 매도 후 흐름·집단 비교가 반영된 서술로 갈아 끼우고 <b>확정</b>한다 (§C-5의 재생성 게이트).
	 *
	 * <p>{@code narrativeFinalized = true}가 곧 "그 뒤 조회에서는 재생성하지 않는다"이다. 이 값을 안 바꾸면
	 * 게이트가 계속 열려 있어 <b>조회마다 LLM을 다시 부르면서 매번 다른 문장이 나가는데 예외도 로그도 없다.</b>
	 *
	 * <p>{@code regenerationAttempts}는 성공에서도 <b>증가한다</b> — 컬럼이 세는 것은 "재생성을 몇 번 시도했는가"
	 * 이고, 성공만 빼면 그 수가 실제 LLM 재호출 횟수와 어긋난다. 상한 판정에는 영향이 없다(성공하면
	 * {@code narrativeFinalized}가 게이트를 먼저 닫는다).
	 *
	 * @param journalFingerprint <b>흐름·집단 사유로 재생성해도 지문을 함께 갱신한다</b> (§FEED-013 결정 3). 그
	 *     프롬프트에도 현재 일기가 실리므로, 갱신하지 않으면 방금 반영한 일기를 다음 조회가 "안 반영됐다"고
	 *     판정해 일기 사유 재생성이 한 번 헛돈다
	 * @param generatedAt 성공에서만 갱신한다. 실패에서 갱신하지 않는 것이 <b>누적 상한을 날짜로 리셋할 수 없는
	 *     이유</b>다 (FEED-007·§C-7)
	 */
	public void applyRegeneratedNarrative(
		String narrative, NarrativeSource narrativeSource, String journalFingerprint, LocalDateTime generatedAt) {
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.narrativeFinalized = true;
		this.regenerationAttempts++;
		this.journalFingerprint = journalFingerprint;
		this.generatedAt = generatedAt;
	}

	/**
	 * <b>투자일기 사유</b> 재생성 성공 — 서술과 지문을 갈아 끼우고 {@code journalRegenerations}만 올린다
	 * (§FEED-013 결정 3, 4차).
	 *
	 * <p><b>{@code narrativeFinalized}를 건드리지 않는 것이 이 메서드의 존재 이유다.</b> 그 플래그는 "매도 후
	 * 흐름·집단 비교까지 반영된 서술인가"이지 "일기까지 반영됐는가"가 아니다. 여기서 {@code true}로 만들면
	 * <b>흐름·집단 게이트가 열리기도 전에 닫혀</b> 그 체결은 매도 후 흐름을 영원히 반영하지 못한다 — 예외도 로그도
	 * 없다. 반대로 일기 판정이 그 플래그를 <b>읽으면</b> 게이트를 이미 통과한 체결에서 일기가 영원히 반영되지
	 * 않는다.
	 *
	 * <p><b>{@code regenerationAttempts}도 건드리지 않는다.</b> 두 카운터가 서로를 소모하면 일기를 여러 번 고친
	 * 체결이 흐름·집단 반영 기회를 잃는다.
	 */
	public void applyJournalRegeneratedNarrative(
		String narrative, NarrativeSource narrativeSource, String journalFingerprint, LocalDateTime generatedAt) {
		this.narrative = narrative;
		this.narrativeSource = narrativeSource;
		this.journalRegenerations++;
		this.journalFingerprint = journalFingerprint;
		this.generatedAt = generatedAt;
	}

	/**
	 * 투자일기 사유 재생성 <b>1회를 소비했다</b>고만 기록한다 — 서술·지문·{@code narrativeFinalized}·
	 * {@code regenerationAttempts}를 전부 그대로 둔다 (§FEED-013 결정 3).
	 *
	 * <p>쓰는 자리가 둘이다. ① 일기 사유 재생성이 템플릿으로 폴백한 실패 — 기존 서술과 지문을 유지해야 하므로
	 * 카운터만 오른다. ② <b>두 사유가 동시에 성립해 LLM을 한 번만 부른 성공</b> — 서술·지문 갱신과 확정은
	 * {@link #applyRegeneratedNarrative}가 하고, 이 메서드가 일기 쪽 카운터를 마저 올린다. 한 번의 생성이 두 사유를
	 * 함께 반영했으므로 <b>두 카운터가 모두 오르는 것이 맞다.</b>
	 */
	public void countJournalRegeneration() {
		this.journalRegenerations++;
	}

	/**
	 * 재생성 <b>실패</b> — 기존 서술을 그대로 두고 재시도 횟수만 누적한다 (FEED-007).
	 *
	 * <p><b>서술을 건드리지 않는 것이 핵심이다.</b> 재생성이 실패하면 {@code NarrativeService}가 템플릿 문장을
	 * 돌려주는데, 그 문장은 매도 후 흐름·집단 비교를 담지 않으므로 기존 문장을 그것으로 덮으면 <b>재생성을
	 * 시도할수록 서술이 빈약해진다.</b>
	 *
	 * <p><b>{@code narrativeFinalized}를 {@code false}로 남기고 {@code generatedAt}도 갱신하지 않는다.</b> 다음
	 * 조회에서 상한 안이면 다시 시도해야 하고, 시각을 갱신하면 "날짜가 바뀌었으니 리셋"이라는 잘못된 규칙이
	 * 성립할 여지가 생긴다 — 상한은 <b>체결 1건당 누적</b>이며 날짜로 리셋하지 않는다 (§C-7).
	 *
	 * <p><b>지문도 갱신하지 않는다</b>(4차). 실패한 재생성의 서술에는 일기가 반영되지 않았는데 지문만 맞춰 두면
	 * 다음 조회가 "일기는 이미 반영됐다"고 판정해 <b>일기가 영원히 반영되지 않는다.</b>
	 */
	public void recordFailedRegeneration() {
		this.regenerationAttempts++;
	}
}
