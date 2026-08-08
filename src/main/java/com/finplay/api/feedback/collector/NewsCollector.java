// 종목 하나의 뉴스를 외부에서 가져오는 수집기 계약 — 구현 선택은 프로필이 가른다.
package com.finplay.api.feedback.collector;

import com.finplay.api.market.domain.Instrument;
import java.util.List;

/**
 * 구현은 둘이다. {@code NaverNewsCollector}가 {@code @Profile({"prod", "news-real"})},
 * {@code FakeNewsCollector}가 {@code @Profile("!prod & !news-real")}로 서로 배타적이다
 * ({@code BithumbRestCandleProvider}/{@code FakeCryptoCandleProvider}의 {@code crypto-real} 선례).
 * 기본 로컬·테스트는 키 없이 Fake로 뜨므로 <b>외부 API 키가 없어도 기동과 자동 테스트가 정상 동작한다</b>
 * (ADR-0011, spec §실패 처리).
 *
 * <p><b>{@code news-real}은 로컬에서 실제 기사를 받아 보는 유일한 방법이다</b> (이슈 #273). 이 문이 없던
 * 동안에는 키를 채운 로컬에서도 기사가 0건이었고, 그 0건이 <b>계약대로의 {@code EMPTY}</b>로 나가 코인 뉴스
 * 수집이 고장 난 것처럼 읽혔다.
 *
 * <p><b>실패는 예외가 아니라 빈 목록이다.</b> 호출이 실패하면 그 종목만 건너뛰고 나머지 종목은 계속 수집한다
 * (§실패 처리). 그래서 이 메서드는 검사 예외도 런타임 예외도 밖으로 내보내지 않는 것을 계약으로 삼는다 —
 * 수집 실패가 분봉 수집·재생세션 확정·주식 시장 개장을 막지 않아야 하기 때문이다(FEED-001).
 *
 * <p><b>발행일자로 거르지 않는다.</b> 받은 기사를 최신순 그대로 넘기고 구간 필터는 조회·매칭 시점에만 건다
 * (FEED-001). 수집 시점에 "원본 거래일 것만" 남기면 D-1 저녁 기사와 D 새벽 기사 중 한쪽이 반드시 버려진다.
 */
public interface NewsCollector {

	/**
	 * 종목 하나의 최신 기사를 가져온다.
	 *
	 * @param instrument 수집 대상 종목. 질의어는 이 종목의 시장·이름에서 나오고(코인만 심볼을 덧붙인다),
	 *     <b>제목 필터의 규칙도 이 종목의 시장이 가른다</b> — 코인과 주식이 서로 반대다 (FEED-001,
	 *     2026-08-07 개정, {@code NewsTitleFilter} javadoc)
	 * @param sameMarketNames 대상과 <b>같은 시장</b>의 종목명 목록. <b>주식</b>에서는 제목에 다른 종목명이 든
	 *     기사를 걸러내는 데 쓰고, <b>코인</b>에서는 자기 이름의 등장이 더 긴 다른 종목명에 삼켜졌는지
	 *     (예: {@code 비트코인} ⊂ {@code 비트코인캐시}) 가리는 데만 쓴다. 대상 자신의 이름이 들어 있어도 되며,
	 *     시장을 섞으면 종목명이 겹치는 순간 정상 기사가 사라지므로 호출부가 같은 시장으로 좁혀 넘긴다.
	 *     종목 목록은 {@code market}의 서비스를 경유해 얻는다(§C-6)
	 * @return 저장 가능한 기사 목록. 실패·키 없음·결과 없음은 모두 빈 목록이다 (오류가 아니다)
	 */
	List<CollectedNewsDto> collect(Instrument instrument, List<String> sameMarketNames);
}
