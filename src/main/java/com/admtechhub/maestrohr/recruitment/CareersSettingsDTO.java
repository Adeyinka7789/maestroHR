package com.admtechhub.maestrohr.recruitment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** HR-facing view of a tenant's public careers page configuration. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareersSettingsDTO {
    private String slug;
    private boolean enabled;
    private String intro;
    /** Full shareable URL, e.g. https://app.maestrohr.com/careers/acme-ab12cd. */
    private String publicUrl;
}
