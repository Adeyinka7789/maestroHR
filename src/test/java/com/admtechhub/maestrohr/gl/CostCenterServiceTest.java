package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterForm;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostCenterServiceTest {

    @Mock CostCenterRepository costCenterRepository;
    @Mock EmployeeRepository employeeRepository;

    @InjectMocks CostCenterService service;

    @BeforeEach void bind() { TenantContext.setCurrentTenant(UUID.randomUUID().toString()); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void create_normalizesCode_andSaves() {
        when(costCenterRepository.existsByCode(any())).thenReturn(false);
        when(costCenterRepository.save(any())).thenAnswer(inv -> {
            CostCenter cc = inv.getArgument(0);
            cc.setId(UUID.randomUUID());
            return cc;
        });

        CostCenterView view = service.create(new CostCenterForm("Lekki Outlet", null, "Lagos", "6001"));

        ArgumentCaptor<CostCenter> cap = ArgumentCaptor.forClass(CostCenter.class);
        verify(costCenterRepository).save(cap.capture());
        assertThat(cap.getValue().getCode()).isEqualTo("LEKKI_OUTLET"); // derived from name
        assertThat(cap.getValue().getLocation()).isEqualTo("Lagos");
        assertThat(cap.getValue().getGlAccountCode()).isEqualTo("6001");
        assertThat(view.name()).isEqualTo("Lekki Outlet");
    }

    @Test
    void create_duplicateCode_autoSuffixes() {
        // "HQ" already taken → the new cost center gets "HQ_2".
        when(costCenterRepository.existsByCode("HQ")).thenReturn(true);
        when(costCenterRepository.existsByCode("HQ_2")).thenReturn(false);
        when(costCenterRepository.save(any())).thenAnswer(inv -> {
            CostCenter cc = inv.getArgument(0);
            cc.setId(UUID.randomUUID());
            return cc;
        });

        CostCenterView view = service.create(new CostCenterForm("HQ", "HQ", null, null));

        assertThat(view.code()).isEqualTo("HQ_2");
    }

    @Test
    void create_blankName_throws() {
        assertThatThrownBy(() -> service.create(new CostCenterForm("  ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }
}
