package com.finplay.api.domain.account.repository;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Market;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

	List<Account> findAllByUserId(Long userId);

	Optional<Account> findByUserIdAndMarket(Long userId, Market market);

	@Query("SELECT a FROM Account a JOIN FETCH a.user WHERE a.user.id = :userId AND a.market = :market")
	Optional<Account> findByUserIdAndMarketFetchUser(@Param("userId")
	Long userId, @Param("market")
	Market market);

	@Query("SELECT a FROM Account a JOIN FETCH a.user WHERE a.id IN :ids")
	List<Account> findAllByIdInFetchUser(@Param("ids")
	List<Long> ids);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.user.id = :userId AND a.market = :market")
	Optional<Account> findByUserIdAndMarketForUpdate(@Param("userId")
	Long userId, @Param("market")
	Market market);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id")
	Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
	List<Account> findByIdInForUpdate(@Param("ids")
	List<Long> ids);
}
