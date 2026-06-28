package com.admtechhub.maestrohr.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {
    private String type;             // "EMAIL" or "SMS"
    private String to;
    private String subject;
    private String templateName;
    private Map<String, Object> variables;
    private byte[] attachmentBytes;  // nullable; PDF payslips arrive Base64-encoded in JSON
    private String attachmentName;
    private String smsMessage;
}
