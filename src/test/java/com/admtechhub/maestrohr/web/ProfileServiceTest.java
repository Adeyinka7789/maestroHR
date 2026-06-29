package com.admtechhub.maestrohr.web;

import com.admtechhub.maestrohr.auth.User;
import com.admtechhub.maestrohr.auth.UserRepository;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @InjectMocks private ProfileService profileService;

    private static final String EMAIL = "hr@acme.com";

    private void withAuthEmail(String email) {
        Authentication auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── 6 ────────────────────────────────────────────────────────────────────

    @Test
    void updatePersonalInfo_updatesCorrectFields() {
        withAuthEmail(EMAIL);
        Employee employee = mock(Employee.class);
        when(employeeRepository.findByEmail(EMAIL)).thenReturn(Optional.of(employee));

        profileService.updatePersonalInfo(
                "John", "Doe", "08012345678", "1 Main St",
                "1990-05-15", "MALE", "SINGLE", null);

        verify(employee).setFirstName("John");
        verify(employee).setLastName("Doe");
        verify(employee).setPhone("08012345678");
        verify(employee).setAddress("1 Main St");
        verify(employeeRepository).save(employee);
    }

    // ── 7 ────────────────────────────────────────────────────────────────────

    @Test
    void updatePersonalInfo_wrongEmployee_throws() {
        withAuthEmail(EMAIL);
        when(employeeRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.updatePersonalInfo(
                "X", "Y", "000", "addr", "1990-01-01", "MALE", "SINGLE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no employee profile");
    }

    // ── 8 ────────────────────────────────────────────────────────────────────

    @Test
    void currentProfile_hasEmployee_returnsFullProfile() {
        withAuthEmail(EMAIL);
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(EMAIL);
        Employee employee = mock(Employee.class);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(employeeRepository.findByEmail(EMAIL)).thenReturn(Optional.of(employee));

        ProfileView view = profileService.currentProfile();

        assertThat(view.hasEmployee()).isTrue();
        assertThat(view.personal()).isNotNull();
    }

    // ── 9 ────────────────────────────────────────────────────────────────────

    @Test
    void currentProfile_noEmployee_returnsSuperAdminProfile() {
        withAuthEmail(EMAIL);
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(employeeRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ProfileView view = profileService.currentProfile();

        assertThat(view.hasEmployee()).isFalse();
        assertThat(view.personal()).isNull();
    }

    // ── 10 ───────────────────────────────────────────────────────────────────

    @Test
    void currentProfile_emailResolvesCorrectly() {
        withAuthEmail(EMAIL);
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(employeeRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ProfileView view = profileService.currentProfile();

        assertThat(view.email()).isEqualTo(EMAIL);
    }
}
