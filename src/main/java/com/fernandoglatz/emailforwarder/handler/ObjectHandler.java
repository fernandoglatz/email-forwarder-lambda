/*
 * Copyright 2022 Fernando Glatz. All Rights Reserved.
 */
package com.fernandoglatz.emailforwarder.handler;

import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fernandoglatz.emailforwarder.util.JsonUtils;

/**
 * @author fernandoglatz
 */
public class ObjectHandler extends AbstractRequestHandler<Object, String> {

	@Override
	@SuppressWarnings("unchecked")
	public String handleRequest(Object input, Context context) {
		String response = super.handleRequest(input, context);
		ObjectMapper objectMapper = JsonUtils.getMapper();

		try {

			if (input instanceof Map) {
				Map<String, Object> inputMap = (Map<String, Object>) input;
				List<Map<String, Object>> records = (List<Map<String, Object>>) inputMap.get("Records");

				if (CollectionUtils.isNotEmpty(records)) {
					SNSEvent event = objectMapper.convertValue(input, new TypeReference<SNSEvent>() {
					});
					SNSHandler snsHandler = new SNSHandler();
					response = snsHandler.handleRequest(event, context);
				}
			}

		} catch (Exception e) {
			logError(e);
			response = "Error on execution";
		} finally {
			try {
				if (StringUtils.isEmpty(response) && input != null) {
					Class<? extends Object> clazz = input.getClass();
					String json = objectMapper.writeValueAsString(input);

					logInfo("Input: " + String.valueOf(clazz) + " - " + json);
				}
			} catch (Exception e) {
				logError(e);
			}
		}

		return response;
	}
}
