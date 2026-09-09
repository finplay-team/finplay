package com.finplay.api.domain.feedback.service;

import java.util.Optional;

public interface NarrativeGenerator {

	Optional<String> generate(String systemPrompt, String userPrompt);
}
