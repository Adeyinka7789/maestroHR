package com.admtechhub.maestrohr.reporting;

public record ReportFile(String filename, String contentType, byte[] content) {
}
