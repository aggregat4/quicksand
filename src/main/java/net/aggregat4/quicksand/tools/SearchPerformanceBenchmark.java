package net.aggregat4.quicksand.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Repeatable local benchmark for the production-equivalent FTS count and page queries. */
public final class SearchPerformanceBenchmark {
  private static final String MATCH_QUERY = "\"project\" AND alp*";
  private static final int PAGE_SIZE = 100;

  private SearchPerformanceBenchmark() {}

  public static void main(String[] args) throws Exception {
    Config config = Config.parse(args);
    System.out.printf(
        Locale.ROOT,
        "Search benchmark: sizes=%s, iterations=%d, query=%s%n",
        config.sizes(),
        config.iterations(),
        MATCH_QUERY);
    for (int messageCount : config.sizes()) {
      runSize(messageCount, config.iterations());
    }
  }

  private static void runSize(int messageCount, int iterations) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      createSchema(connection);
      long indexStart = System.nanoTime();
      seed(connection, messageCount);
      double indexMillis = elapsedMillis(indexStart);

      BenchmarkQuery count =
          new BenchmarkQuery(
              "count",
              """
              SELECT COUNT(*)
              FROM messages m
              JOIN message_search ON message_search.rowid = m.id
              JOIN folders f ON f.id = m.folder_id
              WHERE f.account_id = ? AND message_search MATCH ?
              """);
      BenchmarkQuery newest =
          new BenchmarkQuery(
              "newest page",
              """
              SELECT m.id
              FROM messages m
              JOIN message_search ON message_search.rowid = m.id
              JOIN folders f ON f.id = m.folder_id
              WHERE f.account_id = ? AND message_search MATCH ?
              ORDER BY m.received_date_epoch_s DESC, m.id DESC
              LIMIT ?
              """);
      BenchmarkQuery relevance =
          new BenchmarkQuery(
              "best-match page",
              """
              SELECT m.id, bm25(message_search, 8.0, 2.0, 1.0, 5.0) AS rank
              FROM messages m
              JOIN message_search ON message_search.rowid = m.id
              JOIN folders f ON f.id = m.folder_id
              WHERE f.account_id = ? AND message_search MATCH ?
              ORDER BY rank ASC, m.received_date_epoch_s DESC, m.id DESC
              LIMIT ?
              """);

      System.out.printf(
          Locale.ROOT, "%n%,d messages indexed in %.1f ms%n", messageCount, indexMillis);
      for (BenchmarkQuery query : List.of(count, newest, relevance)) {
        Result result = measure(connection, query, iterations);
        System.out.printf(
            Locale.ROOT,
            "  %-16s median=%7.3f ms  p95=%7.3f ms  rows=%d%n",
            query.name(),
            result.medianMillis(),
            result.p95Millis(),
            result.rows());
      }
    }
  }

  private static Result measure(Connection connection, BenchmarkQuery query, int iterations)
      throws SQLException {
    for (int i = 0; i < 3; i++) {
      execute(connection, query);
    }
    List<Double> timings = new ArrayList<>(iterations);
    int rows = 0;
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      rows = execute(connection, query);
      timings.add(elapsedMillis(start));
    }
    Collections.sort(timings);
    double median = percentile(timings, 0.5);
    double p95 = percentile(timings, 0.95);
    return new Result(median, p95, rows);
  }

  private static int execute(Connection connection, BenchmarkQuery query) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
      statement.setInt(1, 1);
      statement.setString(2, MATCH_QUERY);
      if (!query.name().equals("count")) {
        statement.setInt(3, PAGE_SIZE + 1);
      }
      int rows = 0;
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          rows = query.name().equals("count") ? resultSet.getInt(1) : rows + 1;
        }
      }
      return rows;
    }
  }

  private static void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE folders (id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL)");
      statement.executeUpdate(
          """
          CREATE TABLE messages (
            id INTEGER PRIMARY KEY,
            folder_id INTEGER NOT NULL,
            received_date_epoch_s INTEGER NOT NULL)
          """);
      statement.executeUpdate(
          """
          CREATE VIRTUAL TABLE message_search USING fts5(
            subject, body_excerpt, body, actors,
            tokenize = 'unicode61 remove_diacritics 2')
          """);
      statement.executeUpdate("INSERT INTO folders(id, account_id) VALUES (1, 1), (2, 2)");
    }
  }

  private static void seed(Connection connection, int messageCount) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement message =
            connection.prepareStatement(
                "INSERT INTO messages(id, folder_id, received_date_epoch_s) VALUES (?, ?, ?)");
        PreparedStatement search =
            connection.prepareStatement(
                """
                INSERT INTO message_search(rowid, subject, body_excerpt, body, actors)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      for (int id = 1; id <= messageCount; id++) {
        boolean otherAccount = id % 10 == 0;
        boolean subjectMatch = id % 17 == 0;
        boolean bodyMatch = id % 11 == 0;
        String subject = subjectMatch ? "Project Alpha status " + id : "Mailbox conversation " + id;
        String excerpt = bodyMatch ? "Project alpine planning" : "Routine correspondence";
        String body =
            bodyMatch
                ? "The project alpine planning notes contain representative searchable content."
                : "This is ordinary mailbox body content used to model the local message mirror.";

        message.setInt(1, id);
        message.setInt(2, otherAccount ? 2 : 1);
        message.setLong(3, 1_700_000_000L + id);
        message.addBatch();

        search.setInt(1, id);
        search.setString(2, subject);
        search.setString(3, excerpt);
        search.setString(4, body);
        search.setString(5, "Sender " + id + " sender" + id + "@example.com");
        search.addBatch();

        if (id % 1_000 == 0) {
          message.executeBatch();
          search.executeBatch();
        }
      }
      message.executeBatch();
      search.executeBatch();
      connection.commit();
    } catch (SQLException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static double percentile(List<Double> sorted, double percentile) {
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static double elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000.0;
  }

  private record BenchmarkQuery(String name, String sql) {}

  private record Result(double medianMillis, double p95Millis, int rows) {}

  private record Config(List<Integer> sizes, int iterations) {
    private static Config parse(String[] args) {
      List<Integer> sizes = List.of(10_000, 100_000);
      int iterations = 10;
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--sizes" -> {
            requireValue(args, i);
            sizes =
                Arrays.stream(args[++i].split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .filter(size -> size > 0)
                    .toList();
          }
          case "--iterations" -> {
            requireValue(args, i);
            iterations = Integer.parseInt(args[++i]);
          }
          case "--help", "-h" -> {
            System.out.println(
                "Usage: search-benchmark.sh [--sizes 10000,100000] [--iterations 10]");
            System.exit(0);
          }
          default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
      }
      if (sizes.isEmpty() || iterations < 1) {
        throw new IllegalArgumentException("Sizes and iterations must be positive");
      }
      return new Config(List.copyOf(sizes), iterations);
    }

    private static void requireValue(String[] args, int index) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for " + args[index]);
      }
    }
  }
}
