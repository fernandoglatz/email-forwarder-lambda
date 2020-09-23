/*
 * Copyright 2020 Fernando Glatz. All Rights Reserved.
 */
package com.fernandoglatz.emailforwarder.test;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNSRecord;
import com.fernandoglatz.emailforwarder.handler.SNSHandler;

/**
 * @author fernandoglatz
 */
public class SNSHandlerTest {

	private static final String EXPECTED_RESULT = "Executed";

	@Test
	public void testCall() {
		Context context = null;

		SNS sns = new SNS();
		sns.setMessage("{\"Records\":[]}");

		SNSRecord record = new SNSRecord();
		record.setSns(sns);
		List<SNSRecord> records = Arrays.asList(record);

		SNSEvent event = new SNSEvent();
		event.setRecords(records);

		SNSHandler S3Handler = new SNSHandler();
		String result = S3Handler.handleRequest(event, context);

		Assertions.assertEquals(EXPECTED_RESULT, result);
	}
}
