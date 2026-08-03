// 투자 실습 사전 의도를 저장하는 JPA 리포지토리
package com.finplay.api.education.repository;

import com.finplay.api.education.domain.PracticeIntention;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeIntentionRepository extends JpaRepository<PracticeIntention, Long> {}
