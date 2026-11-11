package com.neuralsoul.memory;

import java.time.Instant;
import java.util.UUID;

public record MemoryRecord(String id, Instant createdAt, String type, String text) {
  public static MemoryRecord manual(String text) {
    return new MemoryRecord(UUID.randomUUID().toString(), Instant.now(), "manual", text);
  }
}
