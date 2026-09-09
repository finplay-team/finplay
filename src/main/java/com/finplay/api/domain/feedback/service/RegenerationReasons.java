package com.finplay.api.domain.feedback.service;

record RegenerationReasons(boolean journal, boolean gate) {

	boolean any() {
		return journal || gate;
	}
}
