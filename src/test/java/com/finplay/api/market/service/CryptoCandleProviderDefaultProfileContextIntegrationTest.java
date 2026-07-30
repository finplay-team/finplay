// 기본 프로필(crypto-real 미적용)에서 코인 캔들 provider가 FakeCryptoCandleProvider 하나뿐임을 고정하는 무회귀 컨텍스트 테스트 (이슈 #107 ⑪)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CryptoCandleProviderDefaultProfileContextIntegrationTest {

	@Autowired
	private ApplicationContext applicationContext;

	// 후보 빈이 둘이면 @Autowired 단일 필드 주입 자체가 실패한다 — 프로필 조건 변경으로 Fake와 실제 구현이
	// 동시에 등록되는 회귀를 이 주입 하나로 잡는다.
	@Autowired
	private CryptoCandleProvider cryptoCandleProvider;

	@Test
	void defaultProfileStillRegistersOnlyFakeCryptoCandleProvider() {
		assertThat(cryptoCandleProvider).isInstanceOf(FakeCryptoCandleProvider.class);
		assertThat(applicationContext.getBeanNamesForType(BithumbRestCandleProvider.class)).isEmpty();
	}
}
