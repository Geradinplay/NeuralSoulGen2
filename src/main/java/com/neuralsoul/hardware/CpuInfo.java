package com.neuralsoul.hardware;

public record CpuInfo(
    String name,
    String architecture,
    int physicalCores,
    int logicalCores
) {}
