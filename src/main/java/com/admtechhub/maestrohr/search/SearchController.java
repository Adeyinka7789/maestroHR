package com.admtechhub.maestrohr.search;

import com.admtechhub.maestrohr.common.ApiResponse;
import com.admtechhub.maestrohr.employee.Employee;
import com.admtechhub.maestrohr.employee.EmployeeDetailsDTO;
import com.admtechhub.maestrohr.employee.EmployeeRepository;
import com.admtechhub.maestrohr.employee.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;   // injected for entity lookup

    @GetMapping
    public ResponseEntity<ApiResponse<SearchService.SearchResponse>> search(@RequestParam String q) {
        // Fetch current employee OUTSIDE the transaction
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Employee currentEmployee = null;
        try {
            EmployeeDetailsDTO dto = employeeService.findByEmail(auth.getName());
            // Use repository to get the full Employee entity (not DTO)
            currentEmployee = employeeRepository.findById(dto.getId()).orElse(null);
        } catch (Exception ignored) {
            // User has no employee profile – this is fine
        }

        return ResponseEntity.ok(ApiResponse.success("Search results", searchService.search(q, currentEmployee)));
    }
}