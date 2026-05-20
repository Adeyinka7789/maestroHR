package com.admtechhub.maestrohr.training;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingProgramDTO {
    private UUID id;
    private String title;
    private String description;
    private String category;
    private Integer durationHours;
    private String trainerName;
    private String trainerEmail;
    private Integer maxParticipants;
    private BigDecimal cost;
    private String status;
    private LocalDateTime createdAt;
}