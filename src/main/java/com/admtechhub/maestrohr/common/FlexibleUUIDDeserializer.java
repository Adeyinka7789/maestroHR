// Create this class
package com.admtechhub.maestrohr.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.UUID;

public class FlexibleUUIDDeserializer extends JsonDeserializer<UUID> {
    @Override
    public UUID deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getText();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // If it's a numeric string, create a UUID from it (not recommended for production)
            // Better: throw a clear error
            throw new IOException("Invalid UUID format. Expected 36-character UUID, got: " + value);
        }
    }
}