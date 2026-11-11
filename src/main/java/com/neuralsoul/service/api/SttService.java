package com.neuralsoul.service.api;

public interface SttService extends ManagedService {
  String convertSpeechToText(byte[] wav) throws Exception;

  /** Compatibility with the spelling requested in the initial API sketch. */
  default String convertSpeekToText(byte[] wav) throws Exception {
    return convertSpeechToText(wav);
  }
}
