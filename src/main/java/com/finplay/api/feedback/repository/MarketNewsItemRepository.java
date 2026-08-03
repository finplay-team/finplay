// 수집한 뉴스·공시의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketNewsItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 * 근거 매칭과 요약·브리핑의 구간 질의는 각자의 이슈가 그 이슈의 완료 조건과 함께 추가한다.
 *
 * <p>지금 있는 것은 수집 시 중복 판정 하나뿐이다.
 */
public interface MarketNewsItemRepository extends JpaRepository<MarketNewsItem, Long> {

	/**
	 * 이미 수집한 기사인지 판정한다. 축은 테이블의 {@code UNIQUE(instrument_id, url)}와 같다 (§데이터 모델).
	 *
	 * <p><b>{@code url} 단독으로 묻지 않는다.</b> "반도체 업황 둔화" 같은 기사는 삼성전자·SK하이닉스 검색 결과에
	 * 모두 나오는데, URL만 보면 먼저 저장된 한 종목만 남고 나머지는 근거 0건이 되어 카드가 생성되지 않는다.
	 */
	boolean existsByInstrumentIdAndUrl(Long instrumentId, String url);
}
