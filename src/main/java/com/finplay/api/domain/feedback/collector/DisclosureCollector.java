// 종목 하나의 공시를 외부에서 가져오는 수집기 계약 — 구현 선택은 프로필이 가른다.
package com.finplay.api.domain.feedback.collector;

import com.finplay.api.domain.market.entity.Instrument;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>공시는 주식만이다</b>(FEED-001). 코인에는 공시가 없다(§C-3). 코인 종목을 넘기는 일 자체가 없도록 호출부가
 * 주식만 골라 부른다.
 *
 * <p>구현은 둘이다. {@code DartDisclosureCollector}가 {@code @Profile("prod")},
 * {@code FakeDisclosureCollector}가 {@code @Profile("!prod")}로 서로 배타적이다 —
 * {@code NewsCollector}와 같은 기준이다.
 *
 * <p><b>실패는 예외가 아니라 빈 목록이다.</b> DART 호출이 실패하면 <b>공시 없이 뉴스만으로 진행한다</b>
 * (§실패 처리). 공시 수집이 뉴스 경로를 막지 않는 것이 이 계약의 핵심이다.
 *
 * <p>반환 타입은 뉴스와 같은 {@code CollectedNewsDto}다. 저장 컬럼이 같고(제목·언론사·원문 URL·발행시각)
 * {@code NEWS}/{@code DISCLOSURE} 구분은 저장 주체가 어느 수집기를 불렀는지로 정하므로 DTO를 따로 두지 않는다.
 * 공시의 {@code publisher}는 {@code DART} 고정이고(§C-8), {@code publishedAt}은 접수일자의 {@code 00:00:00}이다(§C-3).
 */
public interface DisclosureCollector {

	/**
	 * 종목 하나의 최근 공시를 가져온다.
	 *
	 * @param instrument 수집 대상 <b>주식</b> 종목. {@code corp_code} 매핑에 없으면 빈 목록이다 (오류가 아니다)
	 * @param collectionDate 수집 실행 날짜. 조회 구간은 <b>전일부터 당일까지</b>로 잡아 접수 지연분을 잡는다
	 *     (§외부 API 호출 상세)
	 * @return 저장 가능한 공시 목록. 실패·키 없음·매핑 없음·결과 없음은 모두 빈 목록이다
	 */
	List<CollectedNewsDto> collect(Instrument instrument, LocalDate collectionDate);
}
