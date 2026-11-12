package com.neuralsoul.runtime;

import com.neuralsoul.model.ModelRole;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;

public record ModelLaunchConfig(
    String name,
    Path modelPath,
    String device,
    Set<ModelRole> roles,
    int threads,
    int ctxSize,
    int maxOutputTokens,
    int parallel,
    int batchSize,
    int ubatchSize,
    String cacheTypeK,
    String cacheTypeV,
    String isolation,
    int maxVramGb,
    boolean automatic,
    boolean allowSwap,
    boolean allowRamOffload,
    List<LoraLaunchConfig> loras) {}
