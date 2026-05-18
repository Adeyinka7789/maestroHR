package com.admtechhub.maestrohr.performance;

import com.admtechhub.maestrohr.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "review_questions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ReviewQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private ReviewSection section;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    private QuestionType questionType;

    private Integer minRating;
    private Integer maxRating;
    private Integer sortOrder;

    public enum QuestionType {
        RATING, TEXT, YES_NO
    }
}