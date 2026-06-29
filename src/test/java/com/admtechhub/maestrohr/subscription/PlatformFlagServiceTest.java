package com.admtechhub.maestrohr.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformFlagServiceTest {

    @Mock private PlatformFlagRepository flagRepository;

    @InjectMocks private PlatformFlagService service;

    @Test
    void isEnabled_flagExists_returnsValue() {
        PlatformFlag flag = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled("LOAN_MANAGEMENT")).isFalse();
    }

    @Test
    void isEnabled_flagMissing_defaultsToTrue() {
        when(flagRepository.findByName("UNKNOWN_FLAG")).thenReturn(Optional.empty());

        assertThat(service.isEnabled("UNKNOWN_FLAG")).isTrue();
    }

    @Test
    void set_updatesValue() {
        PlatformFlag existing = PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(true).build();
        when(flagRepository.findByName("LOAN_MANAGEMENT")).thenReturn(Optional.of(existing));
        when(flagRepository.save(any(PlatformFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        PlatformFlag result = service.disable("LOAN_MANAGEMENT", "superadmin@maestro.com");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getUpdatedBy()).isEqualTo("superadmin@maestro.com");
        verify(flagRepository).save(existing);
    }

    @Test
    void getAll_returnsAllFlags() {
        List<PlatformFlag> flags = List.of(
                PlatformFlag.builder().name("ATTENDANCE_TRACKING").enabled(true).build(),
                PlatformFlag.builder().name("LOAN_MANAGEMENT").enabled(false).build()
        );
        when(flagRepository.findAllByOrderByNameAsc()).thenReturn(flags);

        List<PlatformFlag> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("ATTENDANCE_TRACKING");
    }
}
