package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.auth.UserRole;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.Gender;
import com.admtechhub.maestrohr.employee.MaritalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the {@link ProfileView} for the authenticated user and applies self-service edits to
 * their own profile. Everything here resolves the subject from the security context — never from
 * a request param — so a user can only ever read or edit their own record (no IDOR surface).
 *
 * <p>The Employee record is optional: SUPER_ADMIN (the platform owner) has a {@code User} but no
 * {@code Employee}, so {@link #currentProfile()} degrades to Account + Security only via
 * {@link ProfileView#hasEmployee()}, mirroring the {@code currentEmployeeOrNull} pattern used by
 * the other self-service web controllers.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;

    private static final DateTimeFormatter DATE_DISPLAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_DISPLAY =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    @Transactional(readOnly = true)
    public ProfileView currentProfile() {
        User user = currentUser();
        Employee employee = currentEmployeeOrNull();

        ProfileView.Personal personal = null;
        ProfileView.Work work = null;
        ProfileView.Bank bank = null;

        if (employee != null) {
            personal = new ProfileView.Personal(
                    employee.getFirstName(),
                    employee.getLastName(),
                    employee.getFullName(),
                    employee.getPhone(),
                    employee.getAddress(),
                    employee.getDateOfBirth(),
                    employee.getGender() != null ? employee.getGender().name() : null,
                    employee.getMaritalStatus() != null ? employee.getMaritalStatus().name() : null,
                    employee.getDeviceEnrollmentId());

            work = new ProfileView.Work(
                    employee.getEmployeeNumber(),
                    employee.getJobTitle(),
                    employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                    employee.getPayGrade() != null ? employee.getPayGrade().getName() : null,
                    employee.getEmploymentType() != null ? employee.getEmploymentType().name() : null,
                    formatDate(employee.getEmploymentStartDate()),
                    formatDate(employee.getProbationEndDate()),
                    employee.getStatus() != null ? employee.getStatus().name() : null);

            bank = new ProfileView.Bank(
                    employee.getBankName(),
                    employee.getBankAccountNumber(),
                    employee.getBankAccountName());
        }

        return new ProfileView(
                employee != null,
                initials(employee, user),
                user.getEmail(),
                formatRole(user.getRole() != null ? user.getRole().name() : null),
                user.isActive(),
                formatDateTime(user.getLastLoginAt()),
                formatDateTime(user.getCreatedAt()),
                user.getRole() == UserRole.SYSTEM_ADMIN,
                personal,
                work,
                bank);
    }

    /**
     * Apply the editable personal fields to the authenticated user's own Employee record.
     * Work, account, and bank fields are deliberately not touched here — those are HR-managed.
     * Throws {@link IllegalArgumentException} if the account has no Employee record (SUPER_ADMIN),
     * which the controller surfaces as an in-place banner.
     */
    @Transactional
    public void updatePersonalInfo(String firstName, String lastName, String phone, String address,
                                   String dateOfBirth, String gender, String maritalStatus, String deviceEnrollmentId) {
        Employee employee = currentEmployeeOrNull();
        if (employee == null) {
            throw new IllegalArgumentException("Your account has no employee profile to edit.");
        }

        employee.setFirstName(requireText(firstName, "First name"));
        employee.setLastName(requireText(lastName, "Last name"));
        employee.setPhone(requireText(phone, "Phone"));
        employee.setAddress(requireText(address, "Address"));
        employee.setDateOfBirth(parseDateOfBirth(dateOfBirth));
        employee.setGender(parseGender(gender));
        employee.setMaritalStatus(parseMaritalStatus(maritalStatus));
        if (deviceEnrollmentId != null) {
            employee.setDeviceEnrollmentId(deviceEnrollmentId.trim());
        }

        employeeRepository.save(employee);
    }

    // ── Resolution helpers (subject always from the security context) ───────────

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));
    }

    /** The authenticated user's Employee record, or null for accounts that aren't employees. */
    private Employee currentEmployeeOrNull() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return employeeRepository.findByEmail(email).orElse(null);
    }

    // ── Formatting / parsing ────────────────────────────────────────────────────

    private String formatDate(java.time.LocalDate date) {
        return date != null ? DATE_DISPLAY.format(date) : "—";
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? DATE_TIME_DISPLAY.format(dateTime) : "—";
    }

    /** "HR_ADMIN" → "Hr Admin". Display only. */
    private String formatRole(String role) {
        if (role == null || role.isBlank()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : role.toLowerCase(Locale.ENGLISH).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private String initials(Employee employee, User user) {
        if (employee != null) {
            String f = employee.getFirstName();
            String l = employee.getLastName();
            String initials = "";
            if (f != null && !f.isBlank()) initials += Character.toUpperCase(f.charAt(0));
            if (l != null && !l.isBlank()) initials += Character.toUpperCase(l.charAt(0));
            if (!initials.isEmpty()) return initials;
        }
        String email = user.getEmail();
        return email != null && !email.isBlank()
                ? String.valueOf(Character.toUpperCase(email.charAt(0)))
                : "?";
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    private java.time.LocalDate parseDateOfBirth(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Date of birth is required.");
        }
        try {
            return java.time.LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Date of birth is not a valid date.");
        }
    }

    private Gender parseGender(String value) {
        try {
            return Gender.valueOf(requireText(value, "Gender"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Please select a valid gender.");
        }
    }

    private MaritalStatus parseMaritalStatus(String value) {
        try {
            return MaritalStatus.valueOf(requireText(value, "Marital status"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Please select a valid marital status.");
        }
    }
}
