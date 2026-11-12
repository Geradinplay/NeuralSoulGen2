package com.neuralsoul.runtime;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.neuralsoul.hardware.*;

/** Dedicated llama.cpp embedding runtime for BGE-M3. */
public final class MemoryEmbeddingRuntimeManager implements AutoCloseable {
  public static final int PORT = 8081;
  private Process process;

  /** Actual operating system process owned by this runtime; -1 if absent. */
  public synchronized long pid() { return process != null && process.isAlive() ? process.pid() : -1L; }

  public synchronized boolean isRunning() { return process != null && process.isAlive(); }

  public synchronized void start(int threads, Path model, String deviceId) throws Exception {
    start(threads, model, deviceId, false, false);
  }

  public synchronized void start(int threads, Path model, String deviceId,
                                 boolean automatic, boolean allowRamOffload) throws Exception {
    if (isRunning()) return;
    Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path executable=root.resolve("runtime").resolve("llama").resolve("llama.exe");
    model = model.toAbsolutePath().normalize();
    if(!Files.isRegularFile(executable)) throw new FileNotFoundException("Не найден runtime/llama/llama.exe: "+executable);
    if(!Files.isRegularFile(model)) throw new FileNotFoundException("Не найдена memory-модель: "+model);

    List<String> command=new ArrayList<>(List.of(
        executable.toString(), "serve", "-m", model.toString(),
        "--host", "127.0.0.1", "--port", Integer.toString(PORT),
        "-t", Integer.toString(Math.max(1,threads)),
        "--embedding", "--pooling", "cls"));
    GpuInfo selectedGpu = selectedGpu(deviceId);
    if (selectedGpu == null) {
      command.add("-ngl"); command.add("0");
    } else {
      command.add("-ngl"); command.add(automatic && allowRamOffload ? "auto" : "all");
      command.add("--main-gpu"); command.add("0");
    }
    System.out.println("[Memory/BGE] Starting: "+String.join(" ",command));
    ProcessBuilder pb=new ProcessBuilder(command);
    if (selectedGpu != null) {
      pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
      String selector = selectedGpu.id();
      if (selector != null && !selector.isBlank()) {
        pb.environment().put("CUDA_VISIBLE_DEVICES", selector);
        System.out.println("[Memory/BGE] Device: " + selectedGpu.name() + " | CUDA_VISIBLE_DEVICES=" + selector);
      }
    }
    pb.directory(executable.getParent().toFile());
    pb.redirectErrorStream(true);
    process=pb.start();
    Process current=process;
    Thread drainer=new Thread(() -> {
      try(BufferedReader r=new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))){
        String line; while((line=r.readLine())!=null) System.out.println("[bge] "+line);
      }catch(IOException ignored){}
    },"bge-runtime-output");
    drainer.setDaemon(true); drainer.start();
    waitReady(current);
    System.out.println("[Memory/BGE] Ready on http://127.0.0.1:"+PORT);
  }

  private GpuInfo selectedGpu(String deviceId) {
    if (deviceId == null || !deviceId.startsWith("GPU ")) return null;
    try {
      int index = Integer.parseInt(deviceId.substring(4).trim());
      HardwareInfo info = HardwareLocator.getInstance().info();
      if (info != null) for (GpuInfo gpu : info.gpus()) if (gpu.index() == index) return gpu;
    } catch (Exception ignored) {}
    return null;
  }

  private void waitReady(Process current)throws Exception{
    HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    URI uri=URI.create("http://127.0.0.1:"+PORT+"/v1/models");
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
    while(System.nanoTime()<deadline){
      if(!current.isAlive()) throw new IllegalStateException("BGE llama.exe завершился при загрузке");
      try{
        HttpResponse<Void> r=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
        if(r.statusCode()>=200&&r.statusCode()<500)return;
      }catch(Exception ignored){}
      Thread.sleep(300);
    }
    throw new TimeoutException("BGE runtime не открыл API на порту "+PORT);
  }

  public synchronized void stop(){
    Process p=process; process=null; if(p==null)return;
    p.destroy();
    try{if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(2,TimeUnit.SECONDS);}}catch(InterruptedException e){Thread.currentThread().interrupt();p.destroyForcibly();}
    System.out.println("[Memory/BGE] stopped");
  }
  public void close(){stop();}
}
