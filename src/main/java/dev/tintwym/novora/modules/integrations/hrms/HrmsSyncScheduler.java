package dev.tintwym.novora.modules.integrations.hrms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-syncs every connected workspace when the API starts and once a night. On a host that sleeps when idle,
 * the startup run is what catches changes made while the API was down.
 */
@Component
public class HrmsSyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(HrmsSyncScheduler.class);

	private final HrmsSettings settings;
	private final HrmsIntegrationService service;
	private final boolean syncOnStartup;

	public HrmsSyncScheduler(HrmsSettings settings, HrmsIntegrationService service,
			@Value("${novora.hrms.sync-on-startup:true}") boolean syncOnStartup) {
		this.settings = settings;
		this.service = service;
		this.syncOnStartup = syncOnStartup;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!syncOnStartup || !settings.isReady()) return;
		Thread.ofVirtual().name("hrms-startup-sync").start(this::syncAll);
	}

	@Scheduled(cron = "${novora.hrms.nightly-sync-cron:0 0 3 * * *}", zone = "UTC")
	public void nightly() {
		if (!settings.isReady()) return;
		syncAll();
		service.pruneProcessedEvents();
	}

	void syncAll() {
		for (String companyId : service.connectedCompanyIds()) {
			try {
				service.reconcile(companyId);
			} catch (RuntimeException ex) {
				log.warn("HRMS sync for company {} failed: {}", companyId, ex.getMessage());
			}
		}
	}
}
