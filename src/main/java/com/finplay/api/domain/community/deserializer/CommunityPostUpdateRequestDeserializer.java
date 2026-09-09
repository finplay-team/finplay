package com.finplay.api.domain.community.deserializer;

import com.finplay.api.domain.community.dto.request.CommunityPostUpdateRequest;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class CommunityPostUpdateRequestDeserializer extends ValueDeserializer<CommunityPostUpdateRequest> {

	@Override
	public CommunityPostUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
		JsonNode node = context.readTree(parser);
		String title = textOrNull(node, "title");
		String content = textOrNull(node, "content");
		boolean instrumentIdProvided = node.has("instrumentId");
		Long instrumentId = instrumentIdProvided && !node.get("instrumentId").isNull()
			? node.get("instrumentId").asLong()
			: null;
		return new CommunityPostUpdateRequest(title, content, instrumentIdProvided, instrumentId);
	}

	private String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}
