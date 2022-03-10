/*
 * Copyright 2020 Fernando Glatz. All Rights Reserved.
 */
package com.fernandoglatz.emailforwarder.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.safety.Safelist;

/**
 * @author fernandoglatz
 */
public class EmailUtils {

	private static final String TEXT_PLAIN = "text/plain";
	private static final String TEXT_HTML = "text/html";
	private static final String LINE_FEED = "\r\n";
	private static final String LINE_FEED_REGEX = "\\R";

	public static String getContent(Message message) throws IOException, MessagingException {
		Set<String> stringParts = new LinkedHashSet<>();

		getContentFromPart(stringParts, message);
		StringBuilder sb = new StringBuilder();

		for (String stringPart : stringParts) {
			if (sb.length() == 0) {
				sb.append(LINE_FEED);
			}

			sb.append(stringPart);
		}

		return sb.toString();
	}

	private static void getContentFromPart(Set<String> stringParts, Part part) throws IOException, MessagingException {
		Object content = part.getContent();

		if (part.isMimeType(TEXT_PLAIN)) {
			String contentStr = String.valueOf(content);
			removeLastLineFeeds(contentStr);
			stringParts.add(contentStr);

		} else if (part.isMimeType(TEXT_HTML)) {
			String contentStr = getContentFromHtmlPart(part);
			stringParts.add(contentStr);

		} else if (content instanceof Multipart) {
			getContentFromMultipart(stringParts, (Multipart) content);
		}
	}

	private static String removeLastLineFeeds(String contentStr) {
		while (contentStr.endsWith(LINE_FEED)) {
			contentStr = StringUtils.chomp(contentStr);
		}
		return contentStr;
	}

	private static String getContentFromHtmlPart(Part part) throws IOException, MessagingException {
		Object content = part.getContent();
		String contentStr = String.valueOf(content).replaceAll(LINE_FEED_REGEX, "");
		Document jsoupDoc = Jsoup.parse(contentStr);

		OutputSettings outputSettings = new OutputSettings();
		outputSettings.prettyPrint(false);
		jsoupDoc.outputSettings(outputSettings);
		jsoupDoc.select("br").before("\\r\\n");
		jsoupDoc.select("p").before("\\r\\n");

		String newStr = jsoupDoc.html().replaceAll("\\\\r\\\\n", LINE_FEED);
		return Jsoup.clean(newStr, StringUtils.EMPTY, Safelist.none(), outputSettings);
	}

	private static void getContentFromMultipart(Set<String> stringParts, Multipart multipart) throws IOException, MessagingException {
		int count = multipart.getCount();

		for (int i = 0; i < count; i++) {
			BodyPart part = multipart.getBodyPart(i);
			getContentFromPart(stringParts, part);
		}
	}

	public static Map<String, InputStream> getAttachments(Message message) throws IOException, MessagingException {
		Map<String, InputStream> attachments = new LinkedHashMap<>();

		getAttachmentsFromPart(attachments, message);

		return attachments;
	}

	private static void getAttachmentsFromMultipart(Map<String, InputStream> attachments, Multipart multipart) throws IOException, MessagingException {
		int count = multipart.getCount();

		for (int i = 0; i < count; i++) {
			BodyPart bodyPart = multipart.getBodyPart(i);
			getAttachmentsFromPart(attachments, bodyPart);
		}
	}

	private static void getAttachmentsFromPart(Map<String, InputStream> attachments, Part part) throws IOException, MessagingException {
		Object content = part.getContent();

		String fileName = part.getFileName();
		if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && StringUtils.isNotEmpty(fileName)) {

			try (InputStream inputStream = part.getInputStream(); //
					ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
				IOUtils.copy(inputStream, outputStream);
				outputStream.flush();
				byte[] bytes = outputStream.toByteArray();
				InputStream newInputStream = new ByteArrayInputStream(bytes);

				attachments.put(fileName, newInputStream);
			}

		} else if (content instanceof Multipart) {
			getAttachmentsFromMultipart(attachments, (Multipart) content);
		}
	}

	public static void sendSMTP(String smtpHost, String smtpPort, String smtpUser, String smtpPassword, MimeMessage mimeMessage) throws MessagingException {
		Properties props = System.getProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.port", Integer.valueOf(smtpPort));
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.auth", "true");

		Session session = Session.getDefaultInstance(props);
		Transport transport = session.getTransport();

		try {
			transport.connect(smtpHost, smtpUser, smtpPassword);
			transport.sendMessage(mimeMessage, mimeMessage.getAllRecipients());
		} finally {
			if (transport != null) {
				transport.close();
			}
		}
	}
}
