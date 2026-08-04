// 수집한 뉴스·공시의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import java.time.LocalDateTime;
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

	/**
	 * 종목·종류·발행시각 구간(<b>양끝 포함</b>)으로 조회한다 — 근거 매칭의 뉴스 쪽 경로다.
	 *
	 * <p><b>종류를 파라미터로 받는 이유는 공시와 규칙이 다르기 때문이다</b>(§C-3). 장중 카드는
	 * {@code NEWS}만 넘겨 공시를 아예 제외하고(FEED-003 — OpenDART가 접수일자만 줘서 분 단위 매칭이
	 * 불가능하다), 시가 갭의 공시는 아래 {@code findDisclosuresReceivedOn}이 날짜 규칙으로 따로 가져온다.
	 * 종류를 안 거르면 {@code 00:00:00}인 공시가 장중 근거창에는 절대 안 들어오고 전장 구간에는 <b>D-1
	 * 접수분이 빠지고 D 접수분만</b> 들어와, 정확히 반대로 걸린다.
	 *
	 * <p>엔티티 전체를 반환하므로 파생 쿼리 이름을 그대로 쓴다 — 위 {@code findExistingUrls}가
	 * {@code @Query}인 것은 단일 필드 프로젝션이기 때문이고 이 메서드는 해당하지 않는다.
	 */
	List<MarketNewsItem> findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
		Long instrumentId, MarketNewsItemType type, LocalDateTime from, LocalDateTime to);

	/**
	 * 그 <b>접수일자 하루</b>에 접수된 공시를 조회한다 (§C-3의 {@code rcept_dt = D-1} 판정).
	 *
	 * <p>공시는 {@code rcept_dt}(YYYYMMDD)만 있어 {@code published_at}이 그 날짜 {@code 00:00:00}이다.
	 * <b>datetime 구간으로 거르면 정확히 반대로 걸린다</b> — {@code D-1} 접수분은 {@code D-1 00:00:00}이라
	 * 전장 시작({@code D-1 15:30})보다 이르러 빠지고, {@code D} 접수분은 {@code D 00:00:00}이라 전장 안에
	 * 들어오는데 거기엔 <b>{@code D} 장중 접수분이 섞여 있다.</b> 그래서 시각 구간이 아니라 날짜로 묻는다.
	 *
	 * <p>경계를 {@code [fromInclusive, toExclusive)} 반열림으로 두는 것은 하루의 끝을
	 * {@code 23:59:59.999999}로 적지 않기 위해서다 — 컬럼이 {@code DATETIME(6)}이라 그 리터럴은 정밀도가
	 * 바뀌는 순간 조용히 틀린다. 호출부는 접수일과 그 다음 날의 {@code 00:00}을 넘긴다.
	 */
	@Query("SELECT n FROM MarketNewsItem n WHERE n.instrument.id = :instrumentId "
		+ "AND n.type = com.finplay.api.feedback.domain.MarketNewsItemType.DISCLOSURE "
		+ "AND n.publishedAt >= :fromInclusive AND n.publishedAt < :toExclusive "
		+ "ORDER BY n.publishedAt ASC")
	List<MarketNewsItem> findDisclosuresReceivedOn(@Param("instrumentId")
	Long instrumentId, @Param("fromInclusive")
	LocalDateTime fromInclusive, @Param("toExclusive")
	LocalDateTime toExclusive);
}
