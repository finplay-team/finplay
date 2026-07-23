// 스프링 컨텍스트가 정상 기동하는지 확인하는 스모크 테스트
package com.finplay.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FinPlayApiApplicationTests {

	@Test
	void contextLoads() {}
}
