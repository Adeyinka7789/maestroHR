package com.admtechhub.maestrohr.attendance;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimal read-only REST endpoint backing the Shift dropdown on the (static, JS-driven)
 * employee edit form — mirrors {@code DepartmentController}/{@code PayGradeController}'s
 * GET-list shape and role gate exactly, since it feeds the same form. Shift CRUD itself is
 * the HTMX {@link com.admtechhub.maestrohr.web.ShiftController} under {@code /htmx/shifts/**}.
 */
@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftApiController {

    private final ShiftRepository shiftRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('HR_ADMIN', 'FINANCE_OFFICER', 'DEPT_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<Shift>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success("Shifts retrieved", shiftRepository.findAllByOrderByNameAsc()));
    }
}
