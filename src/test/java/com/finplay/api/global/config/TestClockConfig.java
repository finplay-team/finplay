// 전역 Clock 빈을 조작 가능한 TestClock으로 교체하는 공용 테스트 설정 (이슈 #134)
package com.finplay.api.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 전역 {@link Clock} 빈(ClockConfig, Asia/Seoul 실시각)을 {@link TestClock}으로 교체한다.
 *
 * <p><b>이 클래스 하나만 쓴다.</b> 테스트마다 중첩 {@code @TestConfiguration}을 따로 두면 설정 클래스가
 * 달라져 Spring 컨텍스트가 그만큼 갈라진다(이슈 #134). 시각이 다른 것은 컨텍스트를 나눌 이유가 아니라
 * {@code @BeforeEach}에서 {@link TestClock#set(LocalDateTime)}으로 세울 값이다.
 *
 * <p>초기값은 의미 없는 자리표시자다 — 기준 시각을 세우지 않은 테스트가 우연히 통과하지 않도록
 * 어떤 테스트도 쓰지 않는 값(2000-01-01)을 넣었다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime PLACEHOLDER = LocalDateTime.of(2000, 1, 1, 0, 0);

	@Bean
	@Primary
	TestClock testClock() {
		return new TestClock(PLACEHOLDER.atZone(KST).toInstant(), KST);
	}
}
