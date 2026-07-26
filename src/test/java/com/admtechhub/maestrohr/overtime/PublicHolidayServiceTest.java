package com.admtechhub.maestrohr.overtime;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicHolidayServiceTest {

    @Mock PublicHolidayRepository repository;
    @InjectMocks PublicHolidayService service;

    @BeforeEach void bind() { TenantContext.setCurrentTenant(UUID.randomUUID().toString()); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void add_savesActiveHoliday() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        when(repository.findByHolidayDate(date)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(date, "  Independence Day  ");

        ArgumentCaptor<PublicHoliday> cap = ArgumentCaptor.forClass(PublicHoliday.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getHolidayDate()).isEqualTo(date);
        assertThat(cap.getValue().getName()).isEqualTo("Independence Day"); // trimmed
        assertThat(cap.getValue().isActive()).isTrue();
    }

    @Test
    void add_duplicateDate_throws() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        when(repository.findByHolidayDate(date)).thenReturn(Optional.of(new PublicHoliday()));

        assertThatThrownBy(() -> service.add(date, "Independence Day"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already set");
        verify(repository, never()).save(any());
    }

    @Test
    void add_blankName_throws() {
        assertThatThrownBy(() -> service.add(LocalDate.of(2026, 10, 1), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void activeDatesBetween_returnsDateSet() {
        LocalDate d1 = LocalDate.of(2026, 7, 1);
        LocalDate d2 = LocalDate.of(2026, 7, 15);
        when(repository.findByActiveTrueAndHolidayDateBetween(any(), any())).thenReturn(List.of(
                PublicHoliday.builder().holidayDate(d1).name("A").active(true).build(),
                PublicHoliday.builder().holidayDate(d2).name("B").active(true).build()));

        Set<LocalDate> dates = service.activeDatesBetween(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(dates).containsExactlyInAnyOrder(d1, d2);
    }
}
