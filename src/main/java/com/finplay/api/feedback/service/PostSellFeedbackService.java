// 매도 직후 피드백 조회의 진입점 — 읽기·LLM 호출·저장을 서로 다른 트랜잭션 경계로 갈라 순서대로 엮는다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.TradeFeedback;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 계약은 {@code docs/api-contracts.md}의 "매도 직후 피드백 조회" 소절이고 요구사항은 spec FEED-007이다.
 * 원장 수치·파생 사실 조립은 {@link PostSellFeedbackReader}가, 저장은 {@link TradeFeedbackWriter}가 한다.
 *
 * <p><b>이 클래스에 {@code @Transactional}이 없는 것이 설계다.</b> spec 012에서 <b>조회 경로에 LLM이 들어오는
 * 첫 자리</b>이고({@code docs/conventions.md}의 "GET은 부수효과 없음"에 대한 이 spec의 유일한 예외, 체결 1건당
 * 1회) LLM 호출이 중앙값 2.5초·p95 3.1초다(#198 실측). 여기에 트랜잭션을 걸면 <b>사용자가 기다리는 그 3초 동안
 * DB 커넥션을 쥐고</b> 있어 커넥션 풀 크기가 곧 동시 조회 수의 상한이 된다. 트랜잭션은 메서드 단위이므로
 * 경계를 좁히는 방법은 협력자를 나누는 것뿐이다 — <b>같은 클래스의 private 메서드에 애노테이션을 붙이면
 * 자기호출이라 프록시를 타지 않아 무효이고, 정확히 막으려던 상태가 조용히 된다.</b>
 *
 * <pre>
 * 1. reader.read(...)           @Transactional(readOnly = true)  — 원장·분봉·카드를 읽고 트랜잭션을 닫는다
 * 2. findByTradeId(...)         리포지터리 기본 트랜잭션          — 기존 서술이 있으면 2·3단계를 건너뛴다
 * 3. narrativeService.resolve   트랜잭션 없음                     — 외부 LLM 호출이 여기 있다
 * 4. writer.create(...)         @Transactional                   — 저장만 감싼다
 * </pre>
 *
 * <p><b>서술은 최초 조회에서 만들어 저장하고 이후 재사용한다</b>(FEED-007, {@code UNIQUE(trade_id)}).
 * <b>재생성은 이 이슈가 하지 않는다</b> — 매도 후 흐름과 집단 비교가 확정된 뒤 1회 갈아 끼우는 경로와
 * {@code narrative_finalized}·누적 상한은 이슈 #208의 5번 항목이 이 흐름 위에 얹는다(§C-5의 재생성 게이트).
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

	private final NarrativeService narrativeService;

	private final TradeFeedbackWriter tradeFeedbackWriter;

	private final TradeFeedbackRepository tradeFeedbackRepository;

	private final Clock clock;

	/**
	 * 본인 매도 체결 1건의 회고를 조회한다.
	 *
	 * @param tradeId 미존재는 404 {@code NOT_FOUND}, 타인 체결은 403 {@code FORBIDDEN}, 매수·코인 체결은 400
	 *     {@code VALIDATION_ERROR}다 — 판정은 {@code reader}가 하며 <b>서술 생성보다 먼저</b>다
	 */
	public PostSellFeedbackResponse getPostSellFeedback(Long userId, Long tradeId) {
		PostSellFeedbackResponse facts = postSellFeedbackReader.read(userId, tradeId);
		NarrativeResultDto narrative = resolveNarrative(userId, tradeId, facts);
		// narrativeStatus는 상수 READY다 — 위 클래스 주석의 근거이며 분기가 없는 것이 의도다.
		return facts.withNarrative(
			narrative.narrative(), narrative.source(), PostSellFeedbackStatus.READY);
	}

	/**
	 * 기존 서술이 있으면 그대로 쓰고, 없으면 만들어 저장한다.
	 *
	 * <p><b>기존 행 조회를 빠뜨리면 조회마다 LLM을 다시 부른다</b> — 호출량이 조회 수에 비례하고 같은 체결의
	 * 문장이 매번 달라지는데 예외도 로그도 없다. 체결은 불변 원장이라 수치가 바뀌지 않으므로 재사용이 정확한
	 * 동작이며, 유일한 예외인 재생성은 5번 항목이 이 분기 안쪽에 얹는다(§C-5).
	 *
	 * <p><b>LLM 실패가 응답을 막지 않는다.</b> 실패·타임아웃·OpenAI 키 없음은 {@code NarrativeService}가 템플릿
	 * 문장으로 흡수하므로(§실패 처리) 여기에 예외 처리가 없는 것이 정상이다 — 수치 요약과 파생 사실은 그대로
	 * 200으로 나간다. 반대로 여기서 {@code try/catch}로 서술을 삼키면 {@code narrative}가 {@code null}인 응답이
	 * 나가면서 "{@code narrativeStatus}는 항상 {@code READY}"가 조용히 깨진다.
	 */
	private NarrativeResultDto resolveNarrative(
		Long userId, Long tradeId, PostSellFeedbackResponse facts) {
		Optional<TradeFeedback> existing = tradeFeedbackRepository.findByTradeId(tradeId);
		if (existing.isPresent()) {
			return new NarrativeResultDto(existing.get().getNarrative(), existing.get().getNarrativeSource());
		}

		NarrativeResultDto resolved = narrativeService.resolvePostSellNarrative(toPromptInput(facts));
		try {
			tradeFeedbackWriter.create(userId, tradeId, resolved, LocalDateTime.now(clock));
		} catch (DataIntegrityViolationException e) {
			// UNIQUE(trade_id) 충돌 — 같은 체결을 동시에 두 번 조회하면(화면 이중 요청, 새로고침 연타) 둘 다
			// "기존 행 없음"을 보고 각자 생성한다. 먼저 커밋한 쪽의 행을 그대로 두고 이번 응답은 방금 만든
			// 문장으로 내린다 — 두 문장 모두 같은 수치에서 나온 관찰형 서술이라 사용자가 보는 내용이 어긋나지
			// 않고, 여기서 500을 내면 조회가 실패한다(§실패 처리의 "UNIQUE 제약으로 무시, 기존 데이터 유지").
			log.debug("매도 회고 서술이 이미 저장돼 있어 이번 저장은 건너뛴다. tradeId={}", tradeId);
		}
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
	 * 다만 {@code peerComparison}은 이 이슈에서 항상 {@code NOT_YET}이고 지표가 전부 {@code null}이므로
	 * <b>넘길 값이 없는 것이 정상 상태다</b> — 7번이 판정을 붙이면 같은 매핑으로 값이 흘러 들어간다.
	 *
	 * <p>시각은 프롬프트가 {@code HH:mm}만 쓰므로 {@code LocalTime}으로 좁힌다. 원본 거래일 축의 날짜는 문장에
	 * 등장하지 않고, 넘기면 모델이 날짜를 서술에 끌어들일 자리만 생긴다.
	 */
	private static PostSellPromptDto toPromptInput(PostSellFeedbackResponse facts) {
		PostSellFlow flow = facts.postSellFlow();
		PeerComparison peer = facts.peerComparison();
		return new PostSellPromptDto(
			facts.name(),
			facts.buyAt().toLocalTime(),
			facts.buyPrice(),
			facts.sellAt().toLocalTime(),
			facts.sellPrice(),
			facts.quantity(),
			facts.returnRate(),
			facts.realizedPnl() == null ? 0L : facts.realizedPnl(),
			facts.holdHighPrice(),
			toLocalTime(facts.holdHighAt()),
			facts.sellVsHighRate(),
			facts.holdLowPrice(),
			toLocalTime(facts.holdLowAt()),
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
			peer == null ? null : peer.yourMinutesToSell());
	}

	/** 보유 구간 카드의 근거 기사 중 가장 이른 발행시각 — {@code buyToNewsMinutes}의 기준값 {@code T0}다. */
	private static LocalTime firstNewsAt(List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.map(LocalDateTime::toLocalTime)
			.orElse(null);
	}

	// 응답 항목과 프롬프트 항목을 따로 두는 것은 #147의 결정이다 — 프롬프트에는 원문 URL을 주지 않고(모델이
	// 인용하려 들 뿐 서술에 쓸모가 없다) 공시 여부는 열거형 대신 boolean으로 받는다.
	private static List<HeldPriceMoveDto> toPromptPriceMoves(List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.map(move -> new HeldPriceMoveDto(
				move.windowStart().toLocalTime(),
				move.windowEnd().toLocalTime(),
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

	private static LocalTime toLocalTime(LocalDateTime value) {
		return value == null ? null : value.toLocalTime();
	}
}
