package com.neuralsoul.bootstrap;

import com.neuralsoul.model.AppState;
import com.neuralsoul.service.PipelineBackend;
import com.neuralsoul.service.ServiceLocator;
import com.neuralsoul.service.SettingsStore;

public record AppContext(
    AppState state,
    PipelineBackend backend,
    SettingsStore settings,
    ServiceLocator services
) {}
