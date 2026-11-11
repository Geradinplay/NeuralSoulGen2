package com.neuralsoul.service.http;

import com.neuralsoul.config.EndpointConfig;
import com.neuralsoul.service.api.ManagedService;
import java.util.function.Supplier;

public abstract class AbstractHttpService implements ManagedService {
  protected final HttpTransport http;
  private final Supplier<EndpointConfig> endpoint;
  private volatile boolean running;

  protected AbstractHttpService(HttpTransport http, Supplier<EndpointConfig> endpoint) {
    this.http = http;
    this.endpoint = endpoint;
  }

  @Override
  public void start() throws Exception {
    running = false;
    http.probe(endpoint.get());
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  protected void requireRunning() {
    if (!running)
      throw new IllegalStateException(
          "Сервис отключён. Нажми «Подключить» или включи автоподключение.");
  }

  protected byte[] request(EndpointConfig c, String path, String method, String type, byte[] body)
      throws Exception {
    requireRunning();
    try {
      return http.request(c, path, method, type, body);
    } catch (Exception e) {
      running = false;
      throw e;
    }
  }
}
