package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NewsTitleFilter {

	public boolean isRelevant(Instrument instrument, List<String> sameMarketNames, String title) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return appearsIndependently(title, instrument.getName(), sameMarketNames);
		}
		return noOtherNameAppears(title, instrument.getName(), sameMarketNames);
	}

	private static boolean appearsIndependently(String title, String selfName, List<String> sameMarketNames) {
		for (int selfStart : occurrenceStarts(title, selfName)) {
			if (!swallowedByLongerName(title, selfStart, selfName, sameMarketNames)) {
				return true;
			}
		}
		return false;
	}

	private static boolean swallowedByLongerName(
		String title, int selfStart, String selfName, List<String> sameMarketNames) {
		for (String otherName : sameMarketNames) {
			if (otherName.length() <= selfName.length()) {
				continue;
			}
			for (int otherStart : occurrenceStarts(title, otherName)) {
				if (otherStart <= selfStart && selfStart + selfName.length() <= otherStart + otherName.length()) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean noOtherNameAppears(String title, String selfName, List<String> sameMarketNames) {
		List<Integer> selfStarts = occurrenceStarts(title, selfName);
		for (String otherName : sameMarketNames) {
			if (selfName.equals(otherName)) {
				continue;
			}
			for (int otherStart : occurrenceStarts(title, otherName)) {
				if (!coveredBySelfName(otherStart, otherName.length(), selfStarts, selfName.length())) {
					return false;
				}
			}
		}
		return true;
	}

	private static List<Integer> occurrenceStarts(String text, String keyword) {
		List<Integer> starts = new ArrayList<>();
		for (int index = text.indexOf(keyword); index >= 0; index = text.indexOf(keyword, index + 1)) {
			starts.add(index);
		}
		return starts;
	}

	private static boolean coveredBySelfName(
		int otherStart, int otherLength, List<Integer> selfStarts, int selfLength) {
		for (int selfStart : selfStarts) {
			if (selfStart <= otherStart && otherStart + otherLength <= selfStart + selfLength) {
				return true;
			}
		}
		return false;
	}
}
