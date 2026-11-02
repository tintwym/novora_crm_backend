package dev.tintwym.novora.modules.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivityReminderScheduler {

	private static final Logger log = LoggerFactory.getLogger(ActivityReminderScheduler.class);

	private final ActivityReminderService reminders;

	public ActivityReminderScheduler(ActivityReminderService reminders) {
		this.reminders = reminders;
	}

	@Scheduled(cron = "${novora.activities.reminder-cron:0 */15 * * * *}", zone = "UTC")
	public void tick() {
		try {
			reminders.sendDueReminders();
		} catch (RuntimeException ex) {
			log.warn("Activity reminder run failed: {}", ex.getMessage());
		}
	}
}
