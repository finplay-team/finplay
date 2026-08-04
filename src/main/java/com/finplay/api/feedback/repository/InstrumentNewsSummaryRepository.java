// 종목·거래일·범위 단위 뉴스 요약의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 필요해진 이슈에서 하나씩 더한다. 조회 경로(주식은 {@code (종목, 거래일, scope)}, 코인은
 * {@code generated_at} 최신 1행)가 쓸 질의는 <b>그 이슈에서 완료 조건과 함께</b> 추가한다.
 */
public interface InstrumentNewsSummaryRepository extends JpaRepository<InstrumentNewsSummary, Long> {

	/**
	 * 그 {@code (종목, 원본 거래일, 범위)} 요약이 이미 있는지 본다 — 축은 테이블의
	 * {@code UNIQUE(instrument_id, origin_trade_date, scope)}와 같다 (§데이터 모델).
	 *
	 * <p><b>주식은 UPSERT가 아니라 "존재 시 건너뜀"이다.</b> 같은 서비스 날짜에 배치가 두 번 돌아도 중복이
	 * 생기지 않아야 하고(배치 ⑤), 이미 만들어 둔 요약을 다시 만들면 <b>LLM만 한 번 더 부르고 결과는 유니크가
	 * 버린다.</b> 코인의 UPSERT는 규칙이 달라({@code generated_at} 갱신, §C-9) 별도 이슈가 그 경로를 더한다.
	 *
	 * <p>선판정은 직렬화를 보장하지 않으므로 유니크 제약이 최종 방어선으로 남는다 — {@code PriceMoveCardService}의
	 * 중복 확인과 같은 형태다.
	 */
	boolean existsByInstrumentIdAndOriginTradeDateAndScope(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope);

	/**
	 * 조회 시각이 보는 요약 1건을 가져온다 — 축은 위 {@code exists}와 같은 유니크 키다.
	 *
	 * <p><b>없는 것이 정상 상태다</b> — 배치가 아직 안 돌았거나 배포 당일이면 행이 없고, 그때 조회는
	 * §C-4 판정 순서 4번({@code EMPTY})으로 읽되 <b>{@code items}는 채운다.</b> 행이 있는데
	 * {@code summary}가 {@code null}인 경우(5번, {@code UNAVAILABLE})와 구분해야 하므로 존재 여부가 아니라
	 * 행 자체를 받아야 한다.
	 */
	Optional<InstrumentNewsSummary> findByInstrumentIdAndOriginTradeDateAndScope(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope);
}
