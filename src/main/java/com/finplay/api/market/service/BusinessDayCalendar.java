// 공휴일 리소스 파일을 1회만 로딩해 영업일 판정·직전 영업일 계산을 제공하는 공용 컴포넌트
package com.finplay.api.market.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// StockReplayService(재생세션 개장 판정) · KisHistoricalCandleCollector(수집 대상 거래일 계산) ·
// StockReplaySessionScheduler(검증 완료 거래일 폴백 탐색)가 각자 같은 리소스 파일을 읽고 주말·공휴일 판정 로직을
// 독립적으로 구현하던 것을 세 번째 중복 시점에 공통화했다(PR #94 리뷰 권장, conventions.md "공통화는 세 번째 중복부터
// 검토"). 세 클래스 모두 이 컴포넌트에 위임하며, 각자의 판정 로직은 더 이상 갖지 않는다.
// 생성자가 공휴일 리소스 파일 로딩 실패 시 예외를 던질 수 있어(SpotBugs CT_CONSTRUCTOR_THROW) 클래스를 final로 선언해
// Finalizer 공격 경로를 차단한다(SpotBugs 권고: "declaring the class final").
@Component
public final class BusinessDayCalendar {

	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";

	// MVP 수준의 2026년 한국 공휴일 목록을 리소스 파일에서 읽어온다 (plan.md: "공휴일 판정은 리소스 파일의 공휴일
	// 목록으로 단순 관리한다"). 정확한 음력 날짜(설날·추석 등) 검증과 연도별 파일 전환은 후속 결정(plan.md Decision
	// Gate, 외부 캘린더 API 미사용).
	private final Set<LocalDate> holidays;

	public BusinessDayCalendar() {
		this.holidays = loadHolidays(HOLIDAYS_RESOURCE_PATH);
	}

	public boolean isBusinessDay(LocalDate date) {
		return !isWeekend(date) && !holidays.contains(date);
	}

	// from의 직전 영업일을 계산한다 — 주말·공휴일을 건너뛴다.
	public LocalDate previousBusinessDay(LocalDate from) {
		LocalDate candidate = from.minusDays(1);
		while (!isBusinessDay(candidate)) {
			candidate = candidate.minusDays(1);
		}
		return candidate;
	}

	private static boolean isWeekend(LocalDate date) {
		DayOfWeek dayOfWeek = date.getDayOfWeek();
		return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
	}

	// 클래스패스 리소스 파일에서 공휴일 목록(한 줄에 yyyy-MM-dd, #으로 시작하는 줄은 주석)을 읽어 Set으로 반환한다.
	private static Set<LocalDate> loadHolidays(String resourcePath) {
		try (InputStream inputStream = BusinessDayCalendar.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("공휴일 리소스 파일을 찾을 수 없습니다: " + resourcePath);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				return reader
					.lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.map(LocalDate::parse)
					.collect(Collectors.toUnmodifiableSet());
			}
		} catch (IOException ex) {
			throw new IllegalStateException("공휴일 리소스 파일을 읽는 중 오류가 발생했습니다: " + resourcePath, ex);
		}
	}
}
