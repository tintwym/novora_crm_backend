package dev.tintwym.novora.modules.integrations.hrms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.tintwym.novora.domain.enums.Role;

class HrmsRoleMapperTest {

	private static final Set<String> SALES = Set.of("SLS", "SALES");
	private static final Set<String> SUPPORT = Set.of("SUPPORT", "CS");

	private static HrmsEmployee employee(String dept, String level, String status) {
		return new HrmsEmployee("e1", "o1", "Ann", "Lee", "ann@example.com", status, dept, null, null, level, null, null);
	}

	private static Optional<Role> role(String dept, String level) {
		return HrmsRoleMapper.roleFor(employee(dept, level, "active"), SALES, SUPPORT);
	}

	@Test
	void salesManagersAndAboveBecomeSalesManagers() {
		assertEquals(Optional.of(Role.SALES_MANAGER), role("SLS", "manager"));
		assertEquals(Optional.of(Role.SALES_MANAGER), role("SLS", "director"));
		assertEquals(Optional.of(Role.SALES_MANAGER), role("sales", "C_LEVEL"));
	}

	@Test
	void otherSalesStaffBecomeReps() {
		assertEquals(Optional.of(Role.SALES_REP), role("SLS", "junior"));
		assertEquals(Optional.of(Role.SALES_REP), role("SLS", "lead"));
		assertEquals(Optional.of(Role.SALES_REP), role("SLS", null));
	}

	@Test
	void supportStaffBecomeAgentsAtAnyLevel() {
		assertEquals(Optional.of(Role.SUPPORT_AGENT), role("CS", "junior"));
		assertEquals(Optional.of(Role.SUPPORT_AGENT), role(" support ", "director"));
	}

	@Test
	void everyoneElseGetsNoAccount() {
		assertEquals(Optional.empty(), role("ENG", "manager"));
		assertEquals(Optional.empty(), role(null, "manager"));
		assertEquals(Optional.empty(), role("", "junior"));
	}

	@Test
	void onlyActiveAndOnLeaveCountAsEmployed() {
		assertTrue(employee("SLS", "junior", "active").isEmployed());
		assertTrue(employee("SLS", "junior", "on_leave").isEmployed());
		assertFalse(employee("SLS", "junior", "terminated").isEmployed());
		assertFalse(employee("SLS", "junior", "inactive").isEmployed());
		assertFalse(employee("SLS", "junior", "suspended").isEmployed());
		assertFalse(employee("SLS", "junior", null).isEmployed());
	}

	@Test
	void parsesStoredCodeLists() {
		assertEquals(Set.of("SLS", "SALES"), HrmsRoleMapper.parseCodes(" sls, SALES ,,"));
		assertEquals(Set.of(), HrmsRoleMapper.parseCodes(null));
		assertEquals(Set.of("OK"), HrmsRoleMapper.parseCodes("ok,bad code,also;bad"));
	}
}
