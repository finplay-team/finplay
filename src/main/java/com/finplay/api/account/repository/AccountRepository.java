// 회원의 시장별 계좌 영속과 사용자별 조회를 담당하는 JPA 리포지터리
package com.finplay.api.account.repository;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

	List<Account> findAllByUserId(Long userId);

	Optional<Account> findByUserIdAndMarket(Long userId, Market market);

	// 랭킹 목록의 닉네임 배치 조회용 — accountId 목록으로 Account+User를 N+1 없이 조회한다.
	@Query("SELECT a FROM Account a JOIN FETCH a.user WHERE a.id IN :ids")
	List<Account> findAllByIdInFetchUser(@Param("ids")
	List<Long> ids);
}
