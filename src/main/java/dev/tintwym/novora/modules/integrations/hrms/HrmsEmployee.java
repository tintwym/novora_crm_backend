package dev.tintwym.novora.modules.integrations.hrms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parts of an HRMS employee snapshot the CRM uses. {@code email} is null once the employee is terminated.
 * Status is one of active, on_leave, inactive, suspended, terminated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HrmsEmployee(
		String employeeId,
		String organizationId,
		String firstName,
		String lastName,
		String email,
		String status,
		String departmentCode,
		String departmentName,
		String positionTitle,
		String positionLevel,
		String managerEmployeeId,
		String departmentManagerEmployeeId) {

	public String fullName() {
		String first = firstName == null ? "" : firstName.trim();
		String last = lastName == null ? "" : lastName.trim();
		return (first + " " + last).trim();
	}

	/** Still works here: on leave keeps access, every other non-active status removes it. */
	public boolean isEmployed() {
		return "active".equalsIgnoreCase(status) || "on_leave".equalsIgnoreCase(status);
	}
}
