package dev.tintwym.novora.modules.integrations.hrms;

import java.time.Instant;
import java.util.Set;

/** A CRM workspace's link to its HRMS organization (one row of {@code hrms_connections}). */
public record HrmsConnection(
		String companyId,
		String hrmsOrgId,
		String hrmsOrgName,
		Set<String> salesCodes,
		Set<String> supportCodes,
		Instant connectedAt,
		Instant lastSyncAt,
		String lastSyncStatus,
		String lastSyncMessage,
		Instant lastEventAt) {
}
