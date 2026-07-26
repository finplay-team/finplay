// 회원의 시장별 계좌 영속과 사용자별 조회를 담당하는 JPA 리포지터리
package com.finplay.api.account.repository;

import com.finplay.api.account.domain.Account;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

	List<Account> findAllByUserId(Long userId);
}
