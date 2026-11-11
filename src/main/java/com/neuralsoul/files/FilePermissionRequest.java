package com.neuralsoul.files;

public record FilePermissionRequest(String operation, String path, String details) {}
