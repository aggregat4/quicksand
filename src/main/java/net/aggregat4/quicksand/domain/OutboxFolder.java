package net.aggregat4.quicksand.domain;

public record OutboxFolder() implements Folder {
  @Override
  public String name() {
    return "Outbox";
  }
}
