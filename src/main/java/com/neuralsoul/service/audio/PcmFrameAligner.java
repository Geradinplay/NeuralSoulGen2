package com.neuralsoul.service.audio;

/** Joins HTTP chunks into complete PCM16 frames before Java Sound sees them.
 * TCP chunk boundaries are arbitrary, including odd-length chunks for mono audio. */
public final class PcmFrameAligner {
  @FunctionalInterface public interface Writer {
    void write(byte[] bytes, int offset, int length) throws InterruptedException;
  }
  private final int frameBytes;
  private final Writer output;
  private final byte[] carry;
  private int carrySize;
  private long deliveredBytes;
  private long inputBytes;
  private long splitFrames;

  public PcmFrameAligner(int channels, Writer output) {
    if (channels < 1 || channels > 2) throw new IllegalArgumentException("PCM channels must be 1 or 2");
    if (output == null) throw new IllegalArgumentException("PCM writer required");
    this.frameBytes = channels * 2;
    this.output = output;
    this.carry = new byte[frameBytes];
  }

  public void accept(byte[] bytes) throws InterruptedException {
    if (bytes == null || bytes.length == 0) return;
    inputBytes += bytes.length;
    int offset = 0;
    if (carrySize != 0) {
      int additional = Math.min(frameBytes - carrySize, bytes.length);
      System.arraycopy(bytes, 0, carry, carrySize, additional);
      carrySize += additional;
      offset += additional;
      if (carrySize != frameBytes) return;
      output.write(carry, 0, frameBytes);
      deliveredBytes += frameBytes;
      carrySize = 0;
      splitFrames++;
    }
    int complete = (bytes.length - offset) / frameBytes * frameBytes;
    if (complete > 0) {
      output.write(bytes, offset, complete);
      deliveredBytes += complete;
      offset += complete;
    }
    int tail = bytes.length - offset;
    if (tail > 0) {
      System.arraycopy(bytes, offset, carry, 0, tail);
      carrySize = tail;
    }
  }

  /** Incomplete final frame signifies truncated/invalid PCM; return number of orphan bytes. */
  public int finish() { return carrySize; }
  public long inputBytes() { return inputBytes; }
  public long deliveredBytes() { return deliveredBytes; }
  public long reassembledFrames() { return splitFrames; }
}
