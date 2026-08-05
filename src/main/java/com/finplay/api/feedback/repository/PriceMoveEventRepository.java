// 변동 구간 카드의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다 — {@code MarketNewsItemRepository}와 같은 이유다.
 * 보유 구간 카드 조회(#6), 코인 쿨다운·일일 상한 카운트(#8)는 각자의 이슈가 그 이슈의 완료 조건과 함께 추가한다.
 */
public interface PriceMoveEventRepository extends JpaRepository<PriceMoveEvent, Long> {

	/**
	 * 같은 카드가 이미 있는지 본다 — 축이 테이블의
	 * {@code UNIQUE(instrument_id, origin_trade_date, event_type, window_start)}와 <b>정확히 같다</b>
	 * (§데이터 모델). 배치가 같은 서비스 날짜에 두 번 돌아도 중복 생성되지 않게 하는 판정이다(§실패 처리).
	 *
	 * <p><b>{@code eventType}을 빼면 안 된다.</b> 장중 루프의 첫 후보는 {@code windowStart = 09:00}인데
	 * 시가 갭 카드의 {@code windowStart}도 첫 분봉 시각이라, 축이 겹치는 순간 나중에 만들어지는 쪽이
	 * "이미 있다"로 판정되어 <b>조용히 사라진다.</b>
	 *
	 * <p>이 판정을 서술 생성보다 <b>먼저</b> 하는 이유는 비용이다 — 뒤로 미루면 재실행 때마다 카드 수만큼
	 * LLM을 다시 부르고 그 결과를 유니크 제약이 버린다.
	 */
	boolean existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
		Long instrumentId, LocalDate originTradeDate, PriceMoveEventType eventType, LocalTime windowStart);

	/**
	 * 노출 시각이 지난 <b>주식</b> 카드만 구간 시작 순으로 조회한다 (§C-5의 카드 게이트).
	 *
	 * <p>게이트가 {@code (서비스 날짜 + reveal_time) <= now()}인데 {@code reveal_time}이 {@code TIME}이므로,
	 * <b>오늘 벽시계 시각과 비교하는 것이 곧 그 식</b>이다. 호출부가 {@code LocalTime.now(clock)}을 넘긴다.
	 * 절대 시각으로 저장했다면 같은 원본 거래일을 두 번 재생할 때 두 번째 날에는 노출 시각이 이미 과거라
	 * <b>09:00에 하루치가 전부 열린다</b>(§노출 판정).
	 *
	 * <p>하루치 카드는 08:45 배치에서 이미 전부 만들어져 있으므로 <b>이 필터가 없으면 오후 사건이 오전에
	 * 노출된다.</b> 조회 자체를 걸러야 하며 응답 조립 단계에서 거르지 않는다.
	 *
	 * <p>코인 카드는 {@code reveal_time}이 {@code NULL}이라 이 비교에서 자연히 빠지지만, 그것에 기대지 않는다 —
	 * 코인은 원본 거래일이 아니라 최근 24시간으로 조회하므로 애초에 다른 질의가 필요하다({@code plan.md} 8번).
	 *
	 * <p><b>{@code window_start}만으로는 순서가 정해지지 않는다.</b> 첫 분봉이 09:00인 날 시가 갭 카드
	 * ({@code window_start} = 첫 분봉 시각)와 장중 첫 후보({@code t − W})가 <b>정확히 같은 값</b>을 갖는다 —
	 * 위 유니크에 {@code event_type}을 넣은 이유로 든 바로 그 상황이다. 2차 키가 없으면 그 두 카드의 순서가
	 * DB 임의 순서가 되어 화면 순서가 실행마다 달라진다.
	 *
	 * <p>2차 키를 {@code event_type}이 아니라 <b>{@code id}</b>로 둔 이유는 둘이다. ① {@code id} 순서는 곧
	 * <b>생성 순서</b>라 §C-6의 배치 단계(3. 시가 갭 → 4. 장중)를 그대로 따르며, 개장 → 장중이라는 읽는
	 * 순서와도 같다. ② {@code event_type}으로 가르면 갭이 먼저인지 장중이 먼저인지가 <b>enum 선언 순서</b>에
	 * 묶여, 다른 이유로 값 순서를 바꾸는 순간 응답 순서가 함께 뒤집히는데 그 연결이 코드 어디에도 보이지 않는다.
	 */
	List<PriceMoveEvent> findByInstrumentIdAndOriginTradeDateAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
		Long instrumentId, LocalDate originTradeDate, LocalTime revealTime);

	/**
	 * 매도 회고의 <b>보유 구간({@code buyAt} ~ {@code sellAt})에 걸친 주식 카드</b>를 노출 게이트까지 적용해
	 * 조회한다 (spec FEED-007·§파생 사실 계산, §C-5의 카드 게이트).
	 *
	 * <p><b>{@code windowEnd}로 구간을 좁힌다.</b> {@code minutesAfterBuy}·{@code minutesBeforeSell}과 반사실의
	 * {@code atFirstMoveAfterBuy}가 전부 {@code windowEnd}의 종가·시각을 기준으로 정의돼 있어(§파생 사실 계산·
	 * §반사실·집단 비교 계산), {@code windowStart}로 좁히면 <b>매도 뒤에 끝난 카드가 보유 구간 카드로 섞이고</b>
	 * {@code minutesBeforeSell}이 음수가 된다 — 보유하지 않은 구간이라 반사실 기준으로도 쓸 수 없는 카드다.
	 *
	 * <p><b>이 조회에도 노출 게이트가 걸린다</b>(게이트 ⑮). 카드의 근거 기사는 {@code windowEnd} 이후
	 * {@code news.match-after-minutes}까지 발행된 것도 붙으므로, 게이트를 빼면 Part A 카드 목록·Part C 기사 목록
	 * 보다 <b>먼저 그 기사를 매도 회고에서 보게 된다.</b> 매도 회고는 보유 구간이 이미 지난 시각이라 안전하다고
	 * 넘기기 쉬운 자리다.
	 *
	 * <p><b>호출부가 {@code revealTime} 상한을 그 체결의 서비스 날짜로 계산한다.</b> {@code reveal_time}이
	 * {@code TIME}이라 {@code LocalTime.now(clock)}을 그대로 넘기는 것은 <b>서비스 날짜가 오늘일 때만</b> §C-5의
	 * {@code (서비스 날짜 + reveal_time) <= now()}와 같다 — 어제 판 체결을 오늘 오전에 조회하면 그날 오후 카드가
	 * 다시 감춰진다(게이트 ⑭이 매도 후 흐름에 대해 막는 것과 같은 되돌림이다). 그래서 판정은
	 * {@code PostSellFeedbackService}가 하고 이 메서드는 받은 상한만 적용한다.
	 *
	 * <p>코인 카드는 {@code window_end}가 {@code NULL}이라 {@code BETWEEN}에서 자연히 빠지지만 그것에 기대지
	 * 않는다 — 매도 회고 자체가 2차에서 주식 전용이고 코인 체결은 400이다.
	 *
	 * <p>정렬 2차 키 {@code id}는 위 메서드와 같은 이유다 — 첫 분봉이 09:00인 날 시가 갭 카드와 장중 첫 후보의
	 * {@code window_start}가 같아 2차 키가 없으면 순서가 DB 임의 순서가 된다. <b>이 응답에서는 그 순서가
	 * 화면 순서만이 아니라 반사실의 기준 카드까지 정한다</b> — {@code atFirstMoveAfterBuy}와 집단 비교의 기준
	 * 카드가 "보유 구간의 첫 카드"이므로, 순서가 흔들리면 <b>같은 체결의 반사실 값이 조회마다 달라진다.</b>
	 */
	List<PriceMoveEvent> findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
		Long instrumentId,
		LocalDate originTradeDate,
		LocalTime windowEndFrom,
		LocalTime windowEndTo,
		LocalTime revealTime);
}
