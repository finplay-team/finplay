// 탐지 결과 1건을 카드로 확정하는 서비스 — 근거 매칭 → 서술 → reveal_time → 저장까지가 이 클래스의 전부다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <b>카드 1건을 확정하는 책임만 진다</b> (spec §C-6). 배치는 이것을 순서대로 부르기만 하며, 코인 감시
 * ({@code CryptoPriceMoveWatcher})도 같은 확정 경로가 필요하므로 이 로직이 배치 안에 있으면 재사용할 수 없다.
 *
 * <p><b>{@code NarrativeService} 하나만 주입한다</b>(§C-6). 카드는 1단계 경로라 후검증에 걸리면 템플릿이고
 * 재생성이 없다 — 생성기·검증기·프롬프트 조립기를 직접 알 필요가 없다.
 *
 * <p><b>쓰기는 {@code price_move_events}·{@code price_move_event_sources} 둘뿐이다.</b> 주문·체결·계좌·잔액·
 * 보유·손익은 물론 {@code instruments}·{@code stock_candles}·{@code market_news_items}에도 쓰지 않는다
 * (8개 이슈 공통 조건인 원장 불변). 쓰기는 전부 {@code PriceMoveCardWriter}를 거치고 그 컴포넌트가 주입하는
 * 리포지토리도 그 두 테이블뿐이라, 원장에 닿는 경로 자체가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMoveCardService {

	// 클램프 기준이자 시가 갭 카드의 노출 시각 (§노출 판정). 기사 노출 판정에 쓰는 벽시계 09:00이며 분봉을
	// 찾는 값이 아니다 (§C-2-1) — "첫 분봉"으로 바꾸면 첫 분봉이 09:03인 날 갭 카드가 3분 늦게 열린다.
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveCardWriter priceMoveCardWriter;

	private final NewsMatcher newsMatcher;

	private final NarrativeService narrativeService;

	private final Clock clock;

	/**
	 * 탐지 결과 1건을 카드로 확정해 저장한다.
	 *
	 * <p><b>이 메서드에는 트랜잭션 경계가 없다.</b> 중복 확인·근거 매칭(읽기)과 서술 생성(외부 LLM 호출, 건당
	 * 최대 20초)이 여기서 일어나므로, 경계를 여기 두면 그 대기 시간 내내 DB 커넥션을 쥐고 있게 되고 개장 전
	 * 배치가 그것을 종목 수만큼 반복한다 — SSE heartbeat와 매분 가격 push가 같은 풀을 쓰는 시간대다.
	 * 경계는 저장 둘에만 필요하므로 {@code PriceMoveCardWriter}로 좁혔다(그 클래스 주석에 근거가 있다).
	 *
	 * <p><b>중복 확인이 서술보다 먼저다.</b> 뒤로 미루면 재실행 때마다 카드 수만큼 LLM을 다시 부르고 그 결과를
	 * 유니크 제약이 버린다. 선판정은 직렬화를 보장하지 않으므로 제약이 최종 방어선으로 남는다.
	 *
	 * @param instrument 주식 종목. 코인 카드는 시각 저장 규칙이 달라(§C-9) 이 경로를 쓰지 않는다
	 * @param originTradeDate 원본 거래일. {@code detection}의 시각이 이 거래일 시간축이다
	 * @return 저장된 카드. <b>근거가 0건이거나 이미 같은 카드가 있으면 {@code Optional.empty()}</b>이며 오류가 아니다
	 */
	public Optional<PriceMoveEvent> confirmStockCard(
		Instrument instrument, LocalDate originTradeDate, PriceMoveDetectionDto detection) {
		if (priceMoveEventRepository.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
			instrument.getId(), originTradeDate, detection.eventType(), detection.windowStart())) {
			log.debug("이미 있는 카드라 건너뛴다. 종목={} 거래일={} 종류={} 구간시작={}",
				instrument.getId(), originTradeDate, detection.eventType(), detection.windowStart());
			return Optional.empty();
		}

		List<MarketNewsItem> sources = newsMatcher.match(instrument.getId(), originTradeDate, detection);
		// 근거가 하나도 없으면 카드를 만들지 않는다 (FEED-003). 근거 없는 문장은 LLM이 지어낸 것이 된다 —
		// 카드가 적게 나온다는 이유로 이 규칙을 완화하지 않는다.
		if (sources.isEmpty()) {
			log.debug("근거 기사가 없어 카드를 만들지 않는다. 종목={} 거래일={} 종류={}",
				instrument.getId(), originTradeDate, detection.eventType());
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolvePriceMoveNarrative(
			toPrompt(instrument, originTradeDate, detection, sources));
		PriceMoveEvent card = PriceMoveEvent.createStock(
			instrument,
			detection.eventType(),
			originTradeDate,
			detection.windowStart(),
			detection.windowEnd(),
			detection.changeRate(),
			detection.detectionScore(),
			narrative.narrative(),
			narrative.source(),
			resolveRevealTime(detection, originTradeDate, sources),
			LocalDateTime.now(clock));
		return Optional.of(priceMoveCardWriter.persist(card, sources));
	}

	/**
	 * 노출 시각을 계산한다 (§노출 판정).
	 *
	 * <pre>
	 * INTRADAY    → max(windowEnd + 1분, clamp(가장 늦은 근거 publishedAt))
	 * OPENING_GAP → clamp(가장 늦은 근거 publishedAt)            ← +1분 없음
	 * </pre>
	 *
	 * <p><b>장중의 {@code +1분}</b> — 캔들 API의 공개 컷오프가 {@code 현재분 − 1분}이라, {@code windowEnd}를
	 * 그대로 쓰면 카드가 그 종가 기반 변동률을 <b>다른 어떤 API보다 1분 먼저</b> 알려준다.
	 *
	 * <p><b>갭 카드에 {@code windowEnd}가 들어가지 않는 이유</b> — 근거가 정의상 전장이라 클램프 결과가 항상
	 * 09:00이고 갭 카드는 개장과 동시에 열려야 한다(§C-6의 생성 순서). {@code +1분}을 더하면 첫 분봉 시각을 따라
	 * 09:01~09:04로 밀려 <b>개장 직후에 갭 카드가 빈다.</b>
	 *
	 * <p><b>근거 기사 시각까지 최댓값에 넣는 이유</b> — 장중 근거창이 {@code windowEnd + 5분}까지라 카드보다
	 * 늦게 발행된 기사가 붙을 수 있고, 그때 {@code windowEnd}로 두면 그 기사를 Part C보다 먼저 보게 된다.
	 */
	private static LocalTime resolveRevealTime(
		PriceMoveDetectionDto detection, LocalDate originTradeDate, List<MarketNewsItem> sources) {
		LocalTime latestSourceRevealTime = clamp(latestPublishedAt(sources), originTradeDate);
		if (detection.eventType() == PriceMoveEventType.OPENING_GAP) {
			return latestSourceRevealTime;
		}
		LocalTime afterWindow = detection.windowEnd().plusMinutes(1);
		return afterWindow.isAfter(latestSourceRevealTime) ? afterWindow : latestSourceRevealTime;
	}

	/**
	 * 발행시각을 노출 시각으로 당긴다 — 원본 거래일 09:00 이전이면 09:00이고 그 외에는 시각 부분 그대로다.
	 *
	 * <p><b>이 클램프가 없으면 시가 갭 카드가 정반대로 작동한다.</b> 직전 거래일 18:40 기사를 근거로 붙인 갭
	 * 카드는 {@code max(09:00, 18:40) = 18:40}이 되어 <b>장중 내내 안 보이고 장 마감 뒤에 나타난다.</b>
	 * 전장 기사는 "개장 시점에 이미 알려진 정보"이므로 09:00으로 당기는 것이 맞다.
	 *
	 * <p>공시는 §C-3의 날짜 규칙을 {@code NewsMatcher}가 먼저 적용하므로 여기 오는 공시는 전부 {@code D-1}
	 * 접수분이고, {@code published_at}이 {@code D-1 00:00:00}이라 항상 09:00이 된다.
	 */
	private static LocalTime clamp(LocalDateTime publishedAt, LocalDate originTradeDate) {
		return publishedAt.isBefore(LocalDateTime.of(originTradeDate, MARKET_OPEN_TIME))
			? MARKET_OPEN_TIME
			: publishedAt.toLocalTime();
	}

	// 근거가 0건이면 이 메서드에 닿기 전에 카드 생성을 접으므로 빈 목록이 들어올 수 없다.
	private static LocalDateTime latestPublishedAt(List<MarketNewsItem> sources) {
		return sources.stream()
			.map(MarketNewsItem::getPublishedAt)
			.max(Comparator.naturalOrder())
			.orElseThrow(() -> new IllegalStateException("근거가 없는 카드는 만들지 않습니다."));
	}

	private static PriceMovePromptDto toPrompt(
		Instrument instrument,
		LocalDate originTradeDate,
		PriceMoveDetectionDto detection,
		List<MarketNewsItem> sources) {
		boolean openingGap = detection.eventType() == PriceMoveEventType.OPENING_GAP;
		return new PriceMovePromptDto(
			instrument.getName(),
			openingGap,
			detection.windowStart(),
			detection.windowEnd(),
			// 갭 카드는 구간이 없어 프롬프트가 이 값을 쓰지 않는다. 주식은 원본 거래일 시간축이라 두 시각의
			// 차가 그대로 구간 길이다 — 자정을 넘는 코인과 달리 음수가 되지 않는다.
			openingGap ? 0 : (int)Duration.between(detection.windowStart(), detection.windowEnd()).toMinutes(),
			detection.changeRate(),
			originTradeDate,
			sources.stream().map(PriceMoveCardService::toSource).toList());
	}

	private static NewsSourceDto toSource(MarketNewsItem item) {
		return new NewsSourceDto(
			item.getTitle(),
			item.getPublisher(),
			item.getPublishedAt(),
			item.getType() == MarketNewsItemType.DISCLOSURE);
	}
}
