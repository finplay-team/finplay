// 계좌·종목별 보유 현황 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.Holding;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

	Optional<Holding> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

	// 테스트 정리(cleanup) 전용 — 계좌 하나의 holding만 좁혀 가져온다. findAll()로 전체 테이블을 스캔한 뒤
	// 계좌 ID로 필터링하면, 공유 Testcontainers MySQL(ADR-0003)에 다른 테스트들이 쌓아 둔 대량의 무관한
	// 행까지 프록시로 초기화하려다 LazyInitializationException·타임아웃 등 정리 자체가 불안정해진다.
	List<Holding> findByAccountId(Long accountId);

	// HoldingService.findHoldingForOwner 전용 — 021 PR #368 리뷰 차단 1: open-in-view=false 운영 환경에서
	// 트랜잭션 종료 후 instrument(LAZY)에 접근하면 LazyInitializationException이 난다. 호출부가 세션이 열린
	// 트랜잭션 안에서 instrument까지 즉시 로딩해 반환하도록 JOIN FETCH로 조회한다.
	@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.id = :id")
	Optional<Holding> findByIdFetchingInstrument(@Param("id")
	Long id);

	// PR #97 리뷰 권장사항 1: ORDER BY 없이는 응답 순서가 DB 임의 순서였다 — 종목 심볼 오름차순으로 고정한다.
	// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001): 튜토리얼 샌드박스 종목 holding은 제외한다.
	@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.account.id = :accountId AND h.isActive = true "
		+ "AND h.instrument.tutorialSample = false ORDER BY h.instrument.symbol ASC")
	List<Holding> findAllByAccountIdAndIsActiveTrue(@Param("accountId")
	Long accountId);

	// 041 tick 순회 전용 — 건너뛴 가상 분마다 순보유수량을 다시 읽는다. 엔티티가 아니라 스칼라로 읽는 이유는
	// ExitPlanFillService·ExitPlanCancelService가 flush() 후 holding을 detach하기 때문이다. 분리된 인스턴스를
	// 재사용하면 낡은 수량을 읽는다(041 plan §tick 알고리즘). 체결을 전부 다시 스캔하지 않는 인덱스 조회다.
	@Query("SELECT h.quantity FROM Holding h WHERE h.account.user.id = :userId AND h.account.market = :market "
		+ "AND h.instrument.id = :instrumentId")
	Optional<java.math.BigDecimal> findQuantityByOwnerAndInstrument(@Param("userId")
	Long userId, @Param("market")
	com.finplay.api.account.domain.Market market, @Param("instrumentId")
	Long instrumentId);

	// 지정가 매도 생성·체결(015-limit-order LMT-001·LMT-002) + 시장가·지정가 매수 체결 공통(PortfolioBuyService.
	// applyBuyTrade, 이슈 #224) holding 락
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT h FROM Holding h WHERE h.account.id = :accountId AND h.instrument.id = :instrumentId")
	Optional<Holding> findByAccountIdAndInstrumentIdForUpdate(@Param("accountId")
	Long accountId, @Param("instrumentId")
	Long instrumentId);
}
