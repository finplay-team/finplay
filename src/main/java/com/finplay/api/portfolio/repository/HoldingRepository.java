// 계좌·종목별 보유 현황 영속을 담당하는 JPA 리포지터리
package com.finplay.api.portfolio.repository;

import com.finplay.api.portfolio.domain.Holding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

	Optional<Holding> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

	@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.account.id = :accountId AND h.isActive = true")
	List<Holding> findAllByAccountIdAndIsActiveTrue(@Param("accountId")
	Long accountId);
}
