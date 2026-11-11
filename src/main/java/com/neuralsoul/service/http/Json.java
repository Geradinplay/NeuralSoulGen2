package com.neuralsoul.service.http;

import java.util.*;

/** Small dependency-free JSON codec for the HTTP adapters. */
public final class Json {
  private Json() {}

  public static String write(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) {
      StringBuilder b = new StringBuilder("\"");
      for (char c : s.toCharArray())
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          default -> {
            if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
          }
        }
      return b.append('"').toString();
    }
    if (value instanceof Boolean) return value.toString();
    if (value instanceof Number n) {
      if (!Double.isFinite(n.doubleValue()))
        throw new IllegalArgumentException("Nonfinite JSON number");
      return n.toString();
    }
    if (value instanceof Map<?, ?> m) {
      StringJoiner j = new StringJoiner(",", "{", "}");
      m.forEach((k, v) -> j.add(write(k.toString()) + ":" + write(v)));
      return j.toString();
    }
    if (value instanceof Iterable<?> list) {
      StringJoiner j = new StringJoiner(",", "[", "]");
      for (Object v : list) j.add(write(v));
      return j.toString();
    }
    throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass());
  }

  public static Object read(String source) {
    Parser p = new Parser(source);
    Object value = p.value(0);
    p.ws();
    if (p.i != source.length()) throw p.error();
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) value;
  }

  public static String text(Object value) {
    if (!(value instanceof String s)) throw new IllegalArgumentException("Expected JSON string");
    return s;
  }

  private static final class Parser {
    private final String s;
    private int i;

    Parser(String s) {
      this.s = s;
    }

    void ws() {
      while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++;
    }

    IllegalArgumentException error() {
      return new IllegalArgumentException("Invalid JSON at position " + i);
    }

    char take() {
      if (i >= s.length()) throw error();
      return s.charAt(i++);
    }

    boolean eat(char c) {
      ws();
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    Object value(int depth) {
      if (depth > 100) throw error();
      ws();
      if (i >= s.length()) throw error();
      char c = s.charAt(i);
      if (c == '"') return string();
      if (c == '{') {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        if (eat('}')) return m;
        do {
          ws();
          String key = string();
          if (!eat(':')) throw error();
          m.put(key, value(depth + 1));
        } while (eat(','));
        if (!eat('}')) throw error();
        return m;
      }
      if (c == '[') {
        i++;
        List<Object> list = new ArrayList<>();
        if (eat(']')) return list;
        do {
          list.add(value(depth + 1));
        } while (eat(','));
        if (!eat(']')) throw error();
        return list;
      }
      for (String literal : List.of("true", "false", "null")) {
        if (s.startsWith(literal, i)) {
          i += literal.length();
          return literal.equals("null") ? null : Boolean.valueOf(literal);
        }
      }
      int start = i;
      while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      String number = s.substring(start, i);
      if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
      return new java.math.BigDecimal(number);
    }

    String string() {
      if (take() != '"') throw error();
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = take();
        if (c == '"') return b.toString();
        if (c < 32) throw error();
        if (c != '\\') {
          b.append(c);
          continue;
        }
        switch (take()) {
          case '"' -> b.append('"');
          case '\\' -> b.append('\\');
          case '/' -> b.append('/');
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) throw error();
            try {
              b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            } catch (NumberFormatException e) {
              throw error();
            }
            i += 4;
          }
          default -> throw error();
        }
      }
    }
  }
}
