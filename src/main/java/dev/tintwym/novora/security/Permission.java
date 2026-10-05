package dev.tintwym.novora.security;

public enum Permission {
	MANAGE_TENANTS("manage:tenants"),
	MANAGE_USERS("manage:users"),
	VIEW_USERS("view:users"),
	MANAGE_COMPANIES("manage:companies"),
	VIEW_COMPANIES("view:companies"),
	MANAGE_CONTACTS("manage:contacts"),
	VIEW_CONTACTS("view:contacts"),
	IMPORT_CONTACTS("import:contacts"),
	MANAGE_DEALS("manage:deals"),
	VIEW_DEALS("view:deals"),
	VIEW_ALL_DEALS("view:all_deals"),
	FORECAST_DEALS("forecast:deals"),
	MANAGE_QUOTES("manage:quotes"),
	VIEW_QUOTES("view:quotes"),
	MANAGE_PRODUCTS("manage:products"),
	MANAGE_AUTOMATIONS("manage:automations"),
	VIEW_AUDIT_LOG("view:audit_log"),
	MANAGE_TICKETS("manage:tickets"),
	VIEW_TICKETS("view:tickets"),
	VIEW_ALL_TICKETS("view:all_tickets"),
	MANAGE_CAMPAIGNS("manage:campaigns"),
	VIEW_CAMPAIGNS("view:campaigns"),
	MANAGE_ACTIVITIES("manage:activities"),
	VIEW_ACTIVITIES("view:activities"),
	VIEW_REPORTS("view:reports"),
	VIEW_DASHBOARD("view:dashboard");

	private final String key;

	Permission(String key) {
		this.key = key;
	}

	public String getKey() {
		return key;
	}
}
