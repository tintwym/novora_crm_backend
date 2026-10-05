package dev.tintwym.novora.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.tintwym.novora.domain.enums.Role;

public final class RolePermissions {

	private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

	static {
		MATRIX.put(Role.SUPER_ADMIN, EnumSet.allOf(Permission.class));
		MATRIX.put(Role.ADMIN, EnumSet.of(
				Permission.MANAGE_USERS, Permission.VIEW_USERS,
				Permission.MANAGE_COMPANIES, Permission.VIEW_COMPANIES,
				Permission.MANAGE_CONTACTS, Permission.VIEW_CONTACTS, Permission.IMPORT_CONTACTS,
				Permission.MANAGE_DEALS, Permission.VIEW_DEALS, Permission.VIEW_ALL_DEALS, Permission.FORECAST_DEALS,
				Permission.MANAGE_QUOTES, Permission.VIEW_QUOTES,
				Permission.MANAGE_TICKETS, Permission.VIEW_TICKETS, Permission.VIEW_ALL_TICKETS,
				Permission.MANAGE_CAMPAIGNS, Permission.VIEW_CAMPAIGNS,
				Permission.MANAGE_ACTIVITIES, Permission.VIEW_ACTIVITIES,
				Permission.MANAGE_PRODUCTS, Permission.MANAGE_AUTOMATIONS, Permission.VIEW_AUDIT_LOG,
				Permission.VIEW_REPORTS, Permission.VIEW_DASHBOARD));
		MATRIX.put(Role.SALES_MANAGER, EnumSet.of(
				Permission.VIEW_USERS,
				Permission.VIEW_COMPANIES, Permission.MANAGE_COMPANIES,
				Permission.MANAGE_CONTACTS, Permission.VIEW_CONTACTS, Permission.IMPORT_CONTACTS,
				Permission.MANAGE_DEALS, Permission.VIEW_DEALS, Permission.VIEW_ALL_DEALS, Permission.FORECAST_DEALS,
				Permission.MANAGE_QUOTES, Permission.VIEW_QUOTES,
				Permission.MANAGE_ACTIVITIES, Permission.VIEW_ACTIVITIES,
				Permission.MANAGE_PRODUCTS, Permission.MANAGE_AUTOMATIONS,
				Permission.VIEW_REPORTS, Permission.VIEW_DASHBOARD));
		MATRIX.put(Role.SALES_REP, EnumSet.of(
				Permission.VIEW_COMPANIES, Permission.MANAGE_COMPANIES,
				Permission.VIEW_CONTACTS, Permission.MANAGE_CONTACTS, Permission.IMPORT_CONTACTS,
				Permission.MANAGE_DEALS, Permission.VIEW_DEALS,
				Permission.MANAGE_QUOTES, Permission.VIEW_QUOTES,
				Permission.MANAGE_ACTIVITIES, Permission.VIEW_ACTIVITIES,
				Permission.VIEW_DASHBOARD));
		MATRIX.put(Role.SUPPORT_AGENT, EnumSet.of(
				Permission.VIEW_COMPANIES, Permission.VIEW_CONTACTS,
				Permission.MANAGE_TICKETS, Permission.VIEW_TICKETS,
				Permission.MANAGE_ACTIVITIES, Permission.VIEW_ACTIVITIES,
				Permission.VIEW_DASHBOARD));
		MATRIX.put(Role.MARKETING_MANAGER, EnumSet.of(
				Permission.VIEW_COMPANIES, Permission.VIEW_CONTACTS,
				Permission.MANAGE_CAMPAIGNS, Permission.VIEW_CAMPAIGNS,
				Permission.VIEW_REPORTS, Permission.VIEW_DASHBOARD));
	}

	private RolePermissions() {}

	public static boolean has(Role role, Permission permission) {
		Set<Permission> perms = MATRIX.get(role);
		return perms != null && perms.contains(permission);
	}

	public static Set<Permission> forRole(Role role) {
		return MATRIX.getOrDefault(role, EnumSet.noneOf(Permission.class));
	}
}
