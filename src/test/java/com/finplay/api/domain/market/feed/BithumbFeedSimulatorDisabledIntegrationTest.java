// 전체 스프링 컨텍스트(Testcontainers)에서 BithumbFeedSimulator 빈이 실제로 생성되지 않는지 단언하는 통합 테스트 (PR #110 리뷰)
package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

// TestcontainersConfiguration의 DynamicPropertyRegistrar 빈이 bithumb.feed.simulate.enabled=false를 실제로
// 적용하고 있는지를 프로퍼티 값이 아니라 빈 생성 결과로 고정한다 — @DynamicPropertySource가 @Import된 설정
// 클래스에서는 조용히 무시되어(PR #110 리뷰로 확인) 이 프로퍼티가 실제로 적용되지 않았던 회귀를 재현·재발 방지한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BithumbFeedSimulatorDisabledIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void bithumbFeedSimulatorBeanIsAbsentWhenSimulateEnabledIsDisabledForTests() {
		assertThat(applicationContext.getBeanNamesForType(BithumbFeedSimulator.class)).isEmpty();
	}
}
