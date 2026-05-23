package net.aggregat4.quicksand.webservice;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

final class SseIo {
  private SseIo() {}

  static boolean writeEvent(OutputStream outputStream, String eventName, String data) {
    try {
      outputStream.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
      outputStream.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      return true;
    } catch (IOException | UncheckedIOException e) {
      return !isClientDisconnect(e);
    }
  }

  static boolean writeComment(OutputStream outputStream, String comment) {
    try {
      outputStream.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      return true;
    } catch (IOException | UncheckedIOException e) {
      return !isClientDisconnect(e);
    }
  }

  static void closeQuietly(OutputStream outputStream) {
    try {
      outputStream.close();
    } catch (IOException e) {
      if (!isClientDisconnect(e)) {
        throw new UncheckedIOException(e);
      }
    } catch (RuntimeException e) {
      if (!isClientDisconnect(e)) {
        throw e;
      }
    }
  }

  static boolean isClientDisconnect(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SocketException) {
        return true;
      }
    }
    return false;
  }
}
