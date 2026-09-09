package com.finplay.api.domain.portfolio.repository;

import com.finplay.api.domain.portfolio.entity.Holding;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

	Optional<Holding> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

	List<Holding> findByAccountId(Long accountId);

	@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.id = :id")
	Optional<Holding> findByIdFetchingInstrument(@Param("id")
	Long id);

	@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.account.id = :accountId AND h.isActive = true "
		+ "AND h.instrument.tutorialSample = false ORDER BY h.instrument.symbol ASC")
	List<Holding> findAllByAccountIdAndIsActiveTrue(@Param("accountId")
	Long accountId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT h FROM Holding h WHERE h.account.id = :accountId AND h.instrument.id = :instrumentId")
	Optional<Holding> findByAccountIdAndInstrumentIdForUpdate(@Param("accountId")
	Long accountId, @Param("instrumentId")
	Long instrumentId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT h FROM Holding h WHERE h.account.id IN :accountIds AND h.instrument.id = :instrumentId ORDER BY h.id ASC")
	List<Holding> findByAccountIdInAndInstrumentIdForUpdate(@Param("accountIds")
	List<Long> accountIds, @Param("instrumentId")
	Long instrumentId);
}
