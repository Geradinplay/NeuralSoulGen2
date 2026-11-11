package com.neuralsoul.files;

@FunctionalInterface
public interface FilePermissionHandler {
  boolean request(FilePermissionRequest request);
}
