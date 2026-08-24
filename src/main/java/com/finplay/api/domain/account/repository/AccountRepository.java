// 회원의 시장별 계좌 영속과 사용자별 조회를 담당하는 JPA 리포지터리
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

	// 내 랭킹 조회(RankingService.getMyRanking)용 — findByUserIdAndMarket과 달리 User를 fetch join으로
	// 함께 가져와 트랜잭션 밖에서도 account.getUser().getNickname()이 안전하다(PR #234 리뷰 권장 반영).
	@Query("SELECT a FROM Account a JOIN FETCH a.user WHERE a.user.id = :userId AND a.market = :market")
	Optional<Account> findByUserIdAndMarketFetchUser(@Param("userId")
	Long userId, @Param("market")
	Market market);

	// 랭킹 목록의 닉네임 배치 조회용 — accountId 목록으로 Account+User를 N+1 없이 조회한다.
	@Query("SELECT a FROM Account a JOIN FETCH a.user WHERE a.id IN :ids")
	List<Account> findAllByIdInFetchUser(@Param("ids")
	List<Long> ids);

	// 지정가 매수 생성 시 계좌 락(015-limit-order LMT-001)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.user.id = :userId AND a.market = :market")
	Optional<Account> findByUserIdAndMarketForUpdate(@Param("userId")
	Long userId, @Param("market")
	Market market);

	// 지정가 체결(015-limit-order LMT-002)·시장가 매도 락 순서 조정용 계좌 락
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id")
	Long id);

	// 청크가 필요로 하는 계좌 전체를 한 번에 잠근다(054-limit-order-fill-bulk-lock). 계좌 ID 오름차순 고정이
	// 필수다 — Account는 종목에 묶이지 않으므로(한 계좌가 여러 종목을 보유), 서로 다른 종목의 청크(=서로 다른
	// 파티션 워커)가 같은 계좌를 동시에 필요로 할 수 있다. 정렬 없이 잠그면 ABBA 데드락 위험이 있다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
	List<Account> findByIdInForUpdate(@Param("ids")
	List<Long> ids);
}
