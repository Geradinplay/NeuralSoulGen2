package com.neuralsoul.config;

import java.nio.file.Path;
import java.util.Properties;

public final class SttConfigLoader extends PropertiesConfigLoader<SttConfig> {
  public SttConfigLoader(Path path) { super(path); }

  protected SttConfig decode(Properties p) {
    SttConfig d = SttConfig.speedDefaults();
    String runtimeDevice = p.getProperty("runtime.device");
    if (runtimeDevice == null || runtimeDevice.isBlank()) {
      // Migrate patch-67 and older configs that stored GPU as a boolean + numeric index.
      boolean useGpu = bool(p, "runtime.useGpu", d.runtimeDevice().startsWith("GPU"));
      int gpuIndex = integer(p, "runtime.gpuDevice", 0);
      runtimeDevice = useGpu ? "GPU " + gpuIndex : "CPU";
    }
    return new SttConfig(
        EndpointProperties.read(p, d.endpoint().baseUrl(), d.endpoint().path(), d.endpoint().healthPath()),
        str(p, "model", d.model()), str(p, "language", d.language()), str(p, "fileField", d.fileField()),
        bool(p, "runtime.enabled", d.runtimeEnabled()),
        str(p, "runtime.executable", d.executablePath()), str(p, "runtime.modelPath", d.modelPath()),
        runtimeDevice, str(p, "runtime.isolation", d.isolation()),
        integer(p, "runtime.threads", d.threads()), integer(p, "runtime.processors", d.processors()),
        bool(p, "runtime.flashAttention", d.flashAttention()),
        integer(p, "decode.maxContext", d.maxContext()), integer(p, "decode.maxLen", d.maxLen()),
        bool(p, "decode.splitOnWord", d.splitOnWord()), integer(p, "decode.bestOf", d.bestOf()),
        integer(p, "decode.beamSize", d.beamSize()), integer(p, "decode.audioContext", d.audioContext()),
        number(p, "decode.wordThreshold", d.wordThreshold()), number(p, "decode.entropyThreshold", d.entropyThreshold()),
        number(p, "decode.logProbThreshold", d.logProbThreshold()), number(p, "decode.noSpeechThreshold", d.noSpeechThreshold()),
        number(p, "decode.temperature", d.temperature()), number(p, "decode.temperatureIncrement", d.temperatureIncrement()),
        bool(p, "decode.noFallback", d.noFallback()), bool(p, "decode.suppressNonSpeech", d.suppressNonSpeech()),
        bool(p, "decode.noTimestamps", d.noTimestamps()), bool(p, "decode.translate", d.translate()),
        bool(p, "vad.enabled", d.vad()), str(p, "vad.modelPath", d.vadModelPath()),
        number(p, "vad.threshold", d.vadThreshold()), integer(p, "vad.minSpeechMs", d.vadMinSpeechMs()),
        integer(p, "vad.minSilenceMs", d.vadMinSilenceMs()), number(p, "vad.maxSpeechSeconds", d.vadMaxSpeechSeconds()),
        integer(p, "vad.speechPadMs", d.vadSpeechPadMs()), number(p, "vad.overlapSeconds", d.vadOverlapSeconds()),
        number(p, "listen.threshold", d.listenThreshold()), number(p, "listen.noiseMultiplier", d.noiseMultiplier()),
        integer(p, "listen.speechStartMs", d.listenSpeechStartMs()), integer(p, "listen.silenceMs", d.listenSilenceMs()),
        integer(p, "listen.preRollMs", d.listenPreRollMs()),
        integer(p, "listen.minSendDelayMs", d.listenMinSendDelayMs()),
        integer(p, "listen.minUtteranceMs", d.listenMinUtteranceMs()),
        integer(p, "listen.maxUtteranceSeconds", d.listenMaxUtteranceSeconds()));
  }

  protected Properties encode(SttConfig c) {
    Properties p = props(
        "model", c.model(), "language", c.language(), "fileField", c.fileField(),
        "runtime.enabled", c.runtimeEnabled(), "runtime.executable", c.executablePath(),
        "runtime.modelPath", c.modelPath(), "runtime.device", c.runtimeDevice(),
        "runtime.threads", c.threads(), "runtime.processors", c.processors(),
        "runtime.flashAttention", c.flashAttention(), "runtime.isolation", c.isolation(),
        "decode.maxContext", c.maxContext(), "decode.maxLen", c.maxLen(),
        "decode.splitOnWord", c.splitOnWord(), "decode.bestOf", c.bestOf(),
        "decode.beamSize", c.beamSize(), "decode.audioContext", c.audioContext(),
        "decode.wordThreshold", c.wordThreshold(), "decode.entropyThreshold", c.entropyThreshold(),
        "decode.logProbThreshold", c.logProbThreshold(), "decode.noSpeechThreshold", c.noSpeechThreshold(),
        "decode.temperature", c.temperature(), "decode.temperatureIncrement", c.temperatureIncrement(),
        "decode.noFallback", c.noFallback(), "decode.suppressNonSpeech", c.suppressNonSpeech(),
        "decode.noTimestamps", c.noTimestamps(), "decode.translate", c.translate(),
        "vad.enabled", c.vad(), "vad.modelPath", c.vadModelPath(), "vad.threshold", c.vadThreshold(),
        "vad.minSpeechMs", c.vadMinSpeechMs(), "vad.minSilenceMs", c.vadMinSilenceMs(),
        "vad.maxSpeechSeconds", c.vadMaxSpeechSeconds(), "vad.speechPadMs", c.vadSpeechPadMs(),
        "vad.overlapSeconds", c.vadOverlapSeconds(), "listen.threshold", c.listenThreshold(),
        "listen.noiseMultiplier", c.noiseMultiplier(), "listen.speechStartMs", c.listenSpeechStartMs(),
        "listen.silenceMs", c.listenSilenceMs(), "listen.preRollMs", c.listenPreRollMs(),
        "listen.minSendDelayMs", c.listenMinSendDelayMs(),
        "listen.minUtteranceMs", c.listenMinUtteranceMs(), "listen.maxUtteranceSeconds", c.listenMaxUtteranceSeconds());
    EndpointProperties.write(p, c.endpoint());
    return p;
  }
}
