package net.aggregat4.quicksand.repository;

import java.util.Collection;
import java.util.stream.Collectors;

final class EnumSql {
  private EnumSql() {}

  static String inClause(Collection<? extends Enum<?>> values) {
    return values.stream().map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
  }

  static String inClause(Enum<?> first, Enum<?>... rest) {
    StringBuilder clause = new StringBuilder("'").append(first.name()).append("'");
    for (Enum<?> value : rest) {
      clause.append(", '").append(value.name()).append("'");
    }
    return clause.toString();
  }

  static <E extends Enum<E>> E optionalEnum(Class<E> enumClass, String value) {
    return value == null ? null : Enum.valueOf(enumClass, value);
  }
}
