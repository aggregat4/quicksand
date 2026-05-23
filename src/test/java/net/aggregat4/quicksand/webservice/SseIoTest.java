package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

class SseIoTest {

  @Test
  void detectsClientDisconnectFromSocketException() {
    assertTrue(SseIo.isClientDisconnect(new SocketException("Broken pipe")));
  }

  @Test
  void detectsClientDisconnectFromUncheckedIOException() {
    assertTrue(
        SseIo.isClientDisconnect(new UncheckedIOException(new SocketException("Broken pipe"))));
  }

  @Test
  void writeEventReturnsFalseWhenClientDisconnects() {
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new UncheckedIOException(new SocketException("Broken pipe"));
          }
        };

    assertFalse(SseIo.writeEvent(broken, "mailbox-updated", "{}"));
  }

  @Test
  void closeQuietlySwallowsClientDisconnect() {
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) {}

          @Override
          public void close() {
            throw new UncheckedIOException(new SocketException("Broken pipe"));
          }
        };

    SseIo.closeQuietly(broken);
  }
}
