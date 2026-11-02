package dev.tintwym.novora.modules.activities;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.tintwym.novora.common.EmailService;
import dev.tintwym.novora.domain.entity.Activity;
import dev.tintwym.novora.domain.entity.UserEntity;
import dev.tintwym.novora.repositories.ActivityRepository;
import dev.tintwym.novora.repositories.UserEntityRepository;

/**
 * Emails assignees about open activities that are overdue or due within the next day.
 * Each activity is emailed once until it is completed and later reopened.
 */
@Service
public class ActivityReminderService {

	private static final Logger log = LoggerFactory.getLogger(ActivityReminderService.class);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

	private final ActivityRepository activities;
	private final UserEntityRepository users;
	private final EmailService email;
	private final long leadSeconds;
	private final int batchSize;

	public ActivityReminderService(ActivityRepository activities, UserEntityRepository users, EmailService email,
			@Value("${novora.activities.reminder-lead-hours:24}") long leadHours,
			@Value("${novora.activities.reminder-batch-size:50}") int batchSize) {
		this.activities = activities;
		this.users = users;
		this.email = email;
		this.leadSeconds = Math.max(1, leadHours) * 3600;
		this.batchSize = Math.max(1, batchSize);
	}

	@Transactional
	public int sendDueReminders() {
		Instant cutoff = Instant.now().plusSeconds(leadSeconds);
		List<Activity> due = activities
				.findByCompletedAtIsNullAndDueAtIsNotNullAndReminderEmailSentAtIsNullAndDueAtLessThanEqualOrderByDueAtAsc(
						cutoff, PageRequest.of(0, batchSize));
		int sent = 0;
		Instant now = Instant.now();
		for (Activity activity : due) {
			UserEntity assignee = users.findById(activity.getUserId()).orElse(null);
			if (assignee == null || !assignee.isActive() || !EmailService.isValidAddress(assignee.getEmail())) {
				activity.setReminderEmailSentAt(now);
				activities.save(activity);
				continue;
			}
			boolean overdue = activity.getDueAt().isBefore(now);
			String subjectLine = activity.getSubject() == null || activity.getSubject().isBlank()
					? activity.getType().name().toLowerCase()
					: activity.getSubject();
			String when = WHEN.format(activity.getDueAt());
			String headline = overdue ? "Overdue: " + subjectLine : "Due soon: " + subjectLine;
			String link = email.appUrl() + "/activities";
			List<String> paragraphs = new ArrayList<>();
			paragraphs.add("Hi " + firstName(assignee.getName()) + ",");
			paragraphs.add(overdue
					? "This task was due at " + when + " and is still open."
					: "This task is due at " + when + ".");
			if (activity.getNote() != null && !activity.getNote().isBlank()) {
				paragraphs.add(activity.getNote().trim());
			}
			email.sendLater(new EmailService.Email(assignee.getEmail(), headline,
					"Hi " + firstName(assignee.getName()) + ",\n\n"
							+ (overdue ? "This task was due at " : "This task is due at ") + when + ".\n"
							+ "Open Novora CRM to review it:\n" + link,
					email.layout(headline, paragraphs, "Open tasks", link)));
			activity.setReminderEmailSentAt(now);
			activities.save(activity);
			sent++;
		}
		if (sent > 0) {
			log.info("Queued {} activity reminder email(s)", sent);
		}
		return sent;
	}

	private static String firstName(String name) {
		if (name == null || name.isBlank()) return "there";
		return name.trim().split("\\s+")[0];
	}
}
