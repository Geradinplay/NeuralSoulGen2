package com.neuralsoul.service.api;

public interface AudioRecorder extends ManagedService {
  byte[] finishRecording() throws Exception;
}
