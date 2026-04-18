package net.aggregat4.quicksand.domain;

public record SearchFolder(Query query) implements Folder {
  @Override
  public String name() {
    return query.query();
  }
}
