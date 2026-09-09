package com.finplay.api.domain.feedback.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public class FakeNarrativeGenerator implements NarrativeGenerator {

	private final Deque<Optional<String>> responses = new ArrayDeque<>();
	private final List<String> systemPrompts = new ArrayList<>();
	private final List<String> userPrompts = new ArrayList<>();

	public FakeNarrativeGenerator enqueue(String narrative) {
		this.responses.add(Optional.of(narrative));
		return this;
	}

	public FakeNarrativeGenerator enqueueFailure() {
		this.responses.add(Optional.empty());
		return this;
	}

	public void reset() {
		this.responses.clear();
		this.systemPrompts.clear();
		this.userPrompts.clear();
	}

	public int callCount() {
		return this.userPrompts.size();
	}

	public List<String> userPrompts() {
		return List.copyOf(this.userPrompts);
	}

	public List<String> systemPrompts() {
		return List.copyOf(this.systemPrompts);
	}

	@Override
	public Optional<String> generate(String systemPrompt, String userPrompt) {
		this.systemPrompts.add(systemPrompt);
		this.userPrompts.add(userPrompt);
		Optional<String> response = this.responses.poll();
		return response == null ? Optional.empty() : response;
	}
}
