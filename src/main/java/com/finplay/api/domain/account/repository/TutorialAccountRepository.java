package com.finplay.api.domain.account.repository;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.market.entity.Market;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorialAccountRepository extends JpaRepository<TutorialAccount, Long> {

	Optional<TutorialAccount> findByUserIdAndMarket(Long userId, Market market);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT ta FROM TutorialAccount ta WHERE ta.user.id = :userId AND ta.market = :market")
	Optional<TutorialAccount> findByUserIdAndMarketForUpdate(@Param("userId")
	Long userId, @Param("market")
	Market market);
}
