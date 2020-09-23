/*
 * Copyright 2020 Fernando Glatz. All Rights Reserved.
 */
package com.fernandoglatz.emailforwarder.handler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNS;
import com.amazonaws.services.lambda.runtime.events.SNSEvent.SNSRecord;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.event.S3EventNotification.S3BucketEntity;
import com.amazonaws.services.s3.event.S3EventNotification.S3Entity;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.event.S3EventNotification.S3ObjectEntity;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.RawMessage;
import com.amazonaws.services.simpleemail.model.SendRawEmailRequest;
import com.fernandoglatz.emailforwarder.util.EmailUtils;

/**
 * @author fernandoglatz
 */
public class SNSHandler extends AbstractRequestHandler<SNSEvent, String> {

	private static final String SMTP_HOST = "smtp_host";
	private static final String SMTP_PORT = "smpt_port";
	private static final String SMTP_USER = "smtp_user";
	private static final String SMTP_PASSWORD = "smtp_password";

	private static final String FROM = "from";
	private static final String DESTINATION = "destination";
	private static final String SUBJECT = "subject";
	private static final String CONTENT = "content";
	private static final String CONTENT_IGNORE_MESSAGE = "content_ignore_message";
	private static final String SEPARATOR = ";";

	private static final String REPLY_TO = "Reply-To";
	private static final String X_ORIGINAL_TO = "X-Original-To";

	@Override
	public String handleRequest(SNSEvent event, Context context) {
		try {
			List<SNSRecord> records = event.getRecords();
			for (SNSRecord record : records) {
				SNS sns = record.getSNS();
				String message = sns.getMessage();

				S3EventNotification s3Event = S3EventNotification.parseJson(message);
				List<S3EventNotificationRecord> s3Records = s3Event.getRecords();

				for (S3EventNotificationRecord s3record : s3Records) {
					S3Entity s3 = s3record.getS3();
					S3BucketEntity bucket = s3.getBucket();
					S3ObjectEntity object = s3.getObject();

					String bucketName = bucket.getName();
					String objectKey = object.getKey();

					AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
					S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucketName, objectKey));

					try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

						try (InputStream inputStream = s3Object.getObjectContent()) {
							IOUtils.copy(inputStream, outputStream);
							outputStream.flush();
						}

						try (InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
							processEmail(inputStream);
						}
					}
				}
			}
		} catch (Exception e) {
			logError(e);
			return "Error on execution";
		}

		return "Executed";
	}

	private void processEmail(InputStream inputStream) throws MessagingException, IOException {
		Map<String, String> env = System.getenv();
		String smtpHost = env.get(SMTP_HOST);
		String smtpPort = env.get(SMTP_PORT);
		String smtpUser = env.get(SMTP_USER);
		String smtpPassword = env.get(SMTP_PASSWORD);
		String from = env.get(FROM);
		String destination = env.get(DESTINATION);
		String subject = env.get(SUBJECT);
		String content = env.get(CONTENT);
		String contentIgnoreMessage = env.get(CONTENT_IGNORE_MESSAGE);
		String[] contentsIgnoreMessage = StringUtils.trimToEmpty(contentIgnoreMessage).split(SEPARATOR);
		String[] destinations = StringUtils.trimToEmpty(destination).split(SEPARATOR);

		MimeMessage receivedMimeMessage = new MimeMessage(null, inputStream);
		String emailSubject = receivedMimeMessage.getSubject();
		String emailContent = EmailUtils.getContent(receivedMimeMessage);
		Object emailOriginalContent = receivedMimeMessage.getContent();
		String emailContentType = receivedMimeMessage.getContentType();
		Address[] emailFrom = receivedMimeMessage.getFrom();
		Address[] emailTo = receivedMimeMessage.getRecipients(Message.RecipientType.TO);

		logInfo("Subject: " + emailSubject);
		//logInfo("Content: " + emailContent);

		boolean subjectMatch = StringUtils.isNotEmpty(subject) ? subject.equalsIgnoreCase(emailSubject) : true;
		boolean contentMatch = StringUtils.isNotEmpty(content) ? StringUtils.containsIgnoreCase(emailContent, content) : true;
		boolean contentIgnoreMatch = false;
		boolean hasDestinations = StringUtils.isNotEmpty(destination);
		boolean smtpConfigured = StringUtils.isNotEmpty(smtpHost);

		if (StringUtils.isNotEmpty(contentIgnoreMessage)) {
			for (String contentIgnore : contentsIgnoreMessage) {
				contentIgnoreMatch |= StringUtils.containsIgnoreCase(emailContent, contentIgnore);
			}
		}

		if (hasDestinations && subjectMatch && contentMatch && !contentIgnoreMatch) {

			Session session = null;
			MimeMessage newMimeMessage = new MimeMessage(session);
			newMimeMessage.setSubject(emailSubject);
			newMimeMessage.setContent(emailOriginalContent, emailContentType);
			newMimeMessage.addHeader(REPLY_TO, InternetAddress.toString(emailFrom));
			newMimeMessage.addHeader(X_ORIGINAL_TO, InternetAddress.toString(emailTo));

			if (StringUtils.isNotEmpty(from)) {
				if (StringUtils.containsNone(from, "<")) {
					InternetAddress address = (InternetAddress) emailFrom[0];
					String personal = address.getPersonal();

					if (StringUtils.isNotEmpty(personal)) {
						from = personal + " <" + from + ">";
					}
				}

				newMimeMessage.setFrom(from);
			} else {
				newMimeMessage.setFrom(InternetAddress.toString(emailFrom));
			}

			for (String email : destinations) {
				newMimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(email));
			}

			logInfo("Sending email to " + destination);

			if (smtpConfigured) {
				EmailUtils.sendSMTP(smtpHost, smtpPort, smtpUser, smtpPassword, newMimeMessage);
			} else {
				sendEmailUsingSES(newMimeMessage);
			}

		} else {
			logInfo("Ignoring email...");
		}
	}

	private void sendEmailUsingSES(MimeMessage mimeMessage) throws IOException, MessagingException {
		AmazonSimpleEmailService sesClient = AmazonSimpleEmailServiceClientBuilder.defaultClient();

		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			mimeMessage.writeTo(outputStream);

			RawMessage rawMessage = new RawMessage(ByteBuffer.wrap(outputStream.toByteArray()));
			SendRawEmailRequest rawEmailRequest = new SendRawEmailRequest(rawMessage);
			sesClient.sendRawEmail(rawEmailRequest);
		}
	}
}
