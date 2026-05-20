package com.admtechhub.maestrohr.exit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClearanceItemDTO {
    private UUID id;
    private String name;
    private String department;
    private Integer sortOrder;
    private Boolean isRequired;
}