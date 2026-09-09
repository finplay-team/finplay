package com.finplay.api.domain.market.service;

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

@Component
public final class BusinessDayCalendar {

	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";

	private final Set<LocalDate> holidays;

	public BusinessDayCalendar() {
		this.holidays = loadHolidays(HOLIDAYS_RESOURCE_PATH);
	}

	public boolean isBusinessDay(LocalDate date) {
		return !isWeekend(date) && !holidays.contains(date);
	}

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
