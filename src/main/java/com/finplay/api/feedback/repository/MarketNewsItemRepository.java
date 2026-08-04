// 수집한 뉴스·공시의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketNewsItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 * 근거 매칭과 요약·브리핑의 구간 질의는 각자의 이슈가 그 이슈의 완료 조건과 함께 추가한다.
 *
 * <p>지금 있는 것은 수집 시 중복 판정 하나뿐이다.
 */
public interface MarketNewsItemRepository extends JpaRepository<MarketNewsItem, Long> {

	/**
	 * 주어진 URL 중 <b>이 종목에 이미 저장된 것</b>을 한 번에 돌려준다. 축은 테이블의
	 * {@code UNIQUE(instrument_id, url)}와 같다 (§데이터 모델).
	 *
	 * <p><b>{@code url} 단독으로 묻지 않는다.</b> "반도체 업황 둔화" 같은 기사는 삼성전자·SK하이닉스 검색 결과에
	 * 모두 나오는데, URL만 보면 먼저 저장된 한 종목만 남고 나머지는 근거 0건이 되어 카드가 생성되지 않는다.
	 *
	 * <p><b>기사 1건마다 묻지 않고 종목 단위로 한 번에 묻는다</b>(PR #174 리뷰 권장). 수집기가 종목당 최대 100건을
	 * 돌려주고 크론이 30분 주기라, 건별로 물으면 실행 하나에 왕복이 수천 번 생긴다. 뒤 이슈(#4·#5)가 이 저장 경로를
	 * 그대로 재사용하므로 여기서 접는다.
	 *
	 * <p><b>파생 쿼리 이름 대신 {@code @Query}를 쓴다.</b> Spring Data JPA는 메서드 이름만으로 단일 필드를
	 * 프로젝션하지 않고 엔티티 전체를 반환하려다 변환에서 실패하는데, <b>컴파일은 통과하므로</b> 슬라이스 테스트를
	 * 돌리기 전까지 드러나지 않는다 ({@code docs/agent-mistakes.md} 2026-08-03).
	 */
	@Query("SELECT n.url FROM MarketNewsItem n WHERE n.instrument.id = :instrumentId AND n.url IN :urls")
	List<String> findExistingUrls(@Param("instrumentId")
	Long instrumentId, @Param("urls")
	Collection<String> urls);
}
