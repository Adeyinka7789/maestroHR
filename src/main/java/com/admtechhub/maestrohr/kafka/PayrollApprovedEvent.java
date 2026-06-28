package com.admtechhub.maestrohr.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PayrollApprovedEvent {
    private UUID payrollRunId;
    private UUID tenantId;
}
