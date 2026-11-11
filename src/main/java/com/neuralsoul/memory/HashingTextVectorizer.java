package com.neuralsoul.memory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class HashingTextVectorizer {
  public static final int DIMENSIONS = 1024;

  public float[] vectorize(String text) {
    float[] v = new float[DIMENSIONS];
    if (text == null || text.isBlank()) return v;
    String normalized = text.toLowerCase(Locale.ROOT);
    String[] words = normalized.split("[^\\p{L}\\p{N}_]+");
    for (String word : words) {
      if (word.isBlank()) continue;
      add(v, word, 1.0f);
      if (word.length() >= 3)
        for (int i=0;i<=word.length()-3;i++) add(v, word.substring(i,i+3), 0.35f);
    }
    double norm=0;
    for(float x:v) norm += x*x;
    if(norm>0) {
      float n=(float)Math.sqrt(norm);
      for(int i=0;i<v.length;i++) v[i]/=n;
    }
    return v;
  }

  private void add(float[] v,String token,float weight) {
    byte[] b=token.getBytes(StandardCharsets.UTF_8);
    long h=0xcbf29ce484222325L;
    for(byte x:b){h^=(x&255);h*=0x100000001b3L;}
    int idx=(int)Math.floorMod(h,DIMENSIONS);
    v[idx] += ((h >>> 63)==0 ? weight : -weight);
  }
}
