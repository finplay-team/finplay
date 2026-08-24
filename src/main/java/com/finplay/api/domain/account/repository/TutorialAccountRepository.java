// 사용자·시장별 튜토리얼 계좌 영속과 비관 잠금 조회를 담당하는 JPA 리포지터리
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

	// 튜토리얼 계좌 get-or-create·리셋 시 동시 매수·매도 경합을 직렬화하기 위한 계좌 락
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT ta FROM TutorialAccount ta WHERE ta.user.id = :userId AND ta.market = :market")
	Optional<TutorialAccount> findByUserIdAndMarketForUpdate(@Param("userId")
	Long userId, @Param("market")
	Market market);
}
