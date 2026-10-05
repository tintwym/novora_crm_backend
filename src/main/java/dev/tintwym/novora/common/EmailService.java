package dev.tintwym.novora.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends email through Gmail SMTP. When no Gmail credentials are configured the message is written to the log
 * instead, so every flow still works locally.
 */
@Service
public class EmailService {

	private static final Logger log = LoggerFactory.getLogger(EmailService.class);
	private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

	public record Attachment(String fileName, byte[] data, String contentType) {}

	public record Email(String to, String subject, String text, String html, List<Attachment> attachments) {
		public Email(String to, String subject, String text, String html) {
			this(to, subject, text, html, List.of());
		}
	}

	private final JavaMailSenderImpl sender;
	private final String username;
	private final String fromName;
	private final String appUrl;
	private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "novora-mail");
		t.setDaemon(true);
		return t;
	});

	public EmailService(
			@Value("${novora.mail.host:smtp.gmail.com}") String host,
			@Value("${novora.mail.port:587}") int port,
			@Value("${novora.mail.username:}") String username,
			@Value("${novora.mail.password:}") String password,
			@Value("${novora.mail.from-name:Novora CRM}") String fromName,
			@Value("${novora.app-url:http://localhost:5173}") String appUrl) {
		this.username = username == null ? "" : username.trim();
		// Google shows App Passwords in groups of four ("abcd efgh ..."); the spaces are not part of it.
		String pass = password == null ? "" : password.replaceAll("\\s", "");
		this.fromName = fromName == null || fromName.isBlank() ? "Novora CRM" : fromName.trim();
		this.appUrl = appUrl == null || appUrl.isBlank() ? "http://localhost:5173" : appUrl.trim().replaceAll("/+$", "");
		if (this.username.isEmpty() || pass.isEmpty()) {
			this.sender = null;
			log.info("Email is not configured (GMAIL_USERNAME / GMAIL_APP_PASSWORD); emails will be logged instead of sent");
			return;
		}
		JavaMailSenderImpl s = new JavaMailSenderImpl();
		s.setHost(host);
		s.setPort(port);
		s.setUsername(this.username);
		s.setPassword(pass);
		s.setDefaultEncoding(StandardCharsets.UTF_8.name());
		Properties props = s.getJavaMailProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.starttls.required", "true");
		props.put("mail.smtp.connectiontimeout", "10000");
		props.put("mail.smtp.timeout", "15000");
		props.put("mail.smtp.writetimeout", "15000");
		this.sender = s;
	}

	public boolean isEnabled() {
		return sender != null;
	}

	public String fromAddress() {
		return isEnabled() ? username : null;
	}

	public String appUrl() {
		return appUrl;
	}

	public static boolean isValidAddress(String email) {
		return email != null && email.length() <= 254 && email.matches(EMAIL_PATTERN);
	}

	/** Sends right away and reports failures to the caller, for actions where the user waits for the result. */
	public void sendNow(Email email) {
		if (!isEnabled()) {
			throw new AppException(
					"Email isn't set up yet. Add GMAIL_USERNAME and GMAIL_APP_PASSWORD to backend/.env and restart the API.",
					HttpStatus.BAD_REQUEST);
		}
		try {
			deliver(email);
		} catch (MailAuthenticationException e) {
			log.warn("Gmail rejected the login for {}: {}", username, e.getMessage());
			throw new AppException("Gmail rejected the login. Check GMAIL_USERNAME and that GMAIL_APP_PASSWORD is an App Password.",
					HttpStatus.BAD_GATEWAY);
		} catch (Exception e) {
			log.warn("Sending email to {} failed: {}", email.to(), e.getMessage());
			throw new AppException("Couldn't send the email. Please try again in a moment.", HttpStatus.BAD_GATEWAY);
		}
	}

	/**
	 * Sends in the background once the current transaction commits, so a rolled-back change never emails anyone
	 * and the request doesn't wait on Gmail. Failures are logged, not thrown.
	 */
	public void sendLater(Email email) {
		Runnable task = () -> background.submit(() -> {
			if (!isEnabled()) {
				log.info("[email not configured] To: {} | Subject: {}\n{}", email.to(), email.subject(), email.text());
				return;
			}
			try {
				deliver(email);
			} catch (Exception e) {
				log.warn("Sending email to {} failed: {}", email.to(), e.getMessage());
			}
		});
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		} else {
			task.run();
		}
	}

	private void deliver(Email email) throws Exception {
		MimeMessage message = sender.createMimeMessage();
		boolean multipart = email.attachments() != null && !email.attachments().isEmpty();
		MimeMessageHelper helper = new MimeMessageHelper(message, multipart || email.html() != null,
				StandardCharsets.UTF_8.name());
		helper.setFrom(new InternetAddress(username, fromName, StandardCharsets.UTF_8.name()));
		helper.setTo(email.to());
		helper.setSubject(email.subject());
		if (email.html() != null) {
			helper.setText(email.text(), email.html());
		} else {
			helper.setText(email.text());
		}
		if (multipart) {
			for (Attachment a : email.attachments()) {
				helper.addAttachment(a.fileName(), new ByteArrayResource(a.data()), a.contentType());
			}
		}
		sender.send(message);
	}

	/** Wraps paragraphs (plain text, escaped here) and an optional button in a simple branded layout. */
	public String layout(String heading, List<String> paragraphs, String buttonLabel, String buttonUrl) {
		StringBuilder body = new StringBuilder();
		for (String p : paragraphs) {
			body.append("<p style=\"margin:0 0 14px;font-size:15px;line-height:1.6;color:#334155\">")
					.append(HtmlUtils.htmlEscape(p).replace("\n", "<br>"))
					.append("</p>");
		}
		if (buttonLabel != null && buttonUrl != null) {
			body.append("<p style=\"margin:22px 0\"><a href=\"").append(HtmlUtils.htmlEscape(buttonUrl))
					.append("\" style=\"display:inline-block;background:#059669;color:#ffffff;text-decoration:none;")
					.append("font-weight:600;padding:12px 22px;border-radius:10px;font-size:15px\">")
					.append(HtmlUtils.htmlEscape(buttonLabel)).append("</a></p>")
					.append("<p style=\"margin:0 0 14px;font-size:13px;line-height:1.6;color:#64748b\">")
					.append("If the button doesn't work, copy this link into your browser:<br>")
					.append("<a href=\"").append(HtmlUtils.htmlEscape(buttonUrl)).append("\" style=\"color:#047857\">")
					.append(HtmlUtils.htmlEscape(buttonUrl)).append("</a></p>");
		}
		return """
				<!doctype html><html><body style="margin:0;background:#f1f5f9;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif">
				<div style="max-width:560px;margin:0 auto;padding:32px 16px">
				<div style="font-size:18px;font-weight:700;color:#047857;margin-bottom:16px">%s</div>
				<div style="background:#ffffff;border:1px solid #e2e8f0;border-radius:16px;padding:28px">
				<h1 style="margin:0 0 16px;font-size:20px;color:#0f172a">%s</h1>%s
				</div>
				<p style="font-size:12px;color:#94a3b8;margin-top:16px">Sent by %s</p>
				</div></body></html>
				""".formatted(HtmlUtils.htmlEscape(fromName), HtmlUtils.htmlEscape(heading), body,
				HtmlUtils.htmlEscape(fromName));
	}

	@PreDestroy
	void shutdown() {
		background.shutdown();
	}
}
