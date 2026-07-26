package com.admtechhub.maestrohr.gl;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeStatus;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterForm;
import com.admtechhub.maestrohr.gl.GlDtos.CostCenterView;
import com.admtechhub.maestrohr.gl.GlDtos.EmployeeAssignRow;
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
import java.util.List;

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

    // ── bulk assignment ──────────────────────────────────────────────────────────

    private Employee employee(String first, String last) {
        Employee e = Employee.builder().firstName(first).lastName(last).employeeNumber("EMP").build();
        e.setId(UUID.randomUUID());
        return e;
    }

    @Test
    void assignToCostCenter_assignsSelectedEmployees() {
        UUID ccId = UUID.randomUUID();
        CostCenter cc = CostCenter.builder().name("Lekki").code("LEKKI").build();
        cc.setId(ccId);
        Employee e1 = employee("A", "One");
        Employee e2 = employee("B", "Two");
        when(costCenterRepository.findById(ccId)).thenReturn(java.util.Optional.of(cc));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(e1, e2));

        int n = service.assignToCostCenter(ccId, List.of(e1.getId(), e2.getId()));

        assertThat(n).isEqualTo(2);
        assertThat(e1.getCostCenter()).isSameAs(cc);
        assertThat(e2.getCostCenter()).isSameAs(cc);
        verify(employeeRepository).saveAll(any());
    }

    @Test
    void assignToCostCenter_nullUnassigns() {
        Employee e1 = employee("A", "One");
        e1.setCostCenter(CostCenter.builder().name("Old").build());
        when(employeeRepository.findAllById(any())).thenReturn(List.of(e1));

        int n = service.assignToCostCenter(null, List.of(e1.getId()));

        assertThat(n).isEqualTo(1);
        assertThat(e1.getCostCenter()).isNull();
        verify(costCenterRepository, never()).findById(any());
    }

    @Test
    void assignToCostCenter_emptyList_throws() {
        assertThatThrownBy(() -> service.assignToCostCenter(UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        verify(employeeRepository, never()).saveAll(any());
    }

    @Test
    void listAssignableEmployees_showsCurrentCostCenter() {
        Employee assigned = employee("Zoe", "A");
        assigned.setCostCenter(CostCenter.builder().name("Lekki").build());
        Employee unassigned = employee("Ada", "B");
        when(employeeRepository.findByStatus(EmployeeStatus.ACTIVE)).thenReturn(List.of(assigned, unassigned));

        List<EmployeeAssignRow> rows = service.listAssignableEmployees();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Ada B"); // sorted case-insensitively by name
        assertThat(rows.get(1).currentCostCenter()).isEqualTo("Lekki");
    }
}
