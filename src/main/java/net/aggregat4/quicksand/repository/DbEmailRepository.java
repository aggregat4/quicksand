package net.aggregat4.quicksand.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.PageDirection;
import net.aggregat4.quicksand.domain.PageParams;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.search.SearchQueryUtils;

public class DbEmailRepository implements EmailRepository {
  private static final int IN_BATCH_SIZE = 100;
  private static final String MESSAGE_HEADER_COLUMNS =
      """
            id, imap_uid, subject, sent_date, sent_date_epoch_s, received_date, received_date_epoch_s, body_excerpt, starred, read
            """;
  private static final String QUALIFIED_MESSAGE_HEADER_COLUMNS =
      """
            m.id, m.imap_uid, m.subject, m.sent_date, m.sent_date_epoch_s, m.received_date, m.received_date_epoch_s, m.body_excerpt, m.starred, m.read
            """;
  private static final String MESSAGE_VIEWER_COLUMNS =
      MESSAGE_HEADER_COLUMNS + ", body, plain_text";
  private final DataSource ds;

  public DbEmailRepository(DataSource ds, DbActorRepository actorRepository) {
    this.ds = ds;
  }

  @Override
  public Optional<Email> findById(int id) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT " + MESSAGE_VIEWER_COLUMNS + " FROM messages WHERE id = ?",
        stmt -> {
          stmt.setInt(1, id);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  return Optional.empty();
                }
                List<Actor> actors = getActors(id);
                return Optional.of(convertRowToViewerEmail(rs, actors));
              });
        });
  }

  @Override
  public Optional<Email> findByMessageUid(long uid) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT " + MESSAGE_HEADER_COLUMNS + " FROM messages WHERE imap_uid = ?",
        stmt -> {
          stmt.setLong(1, uid);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  return Optional.of(convertRowToListEmail(rs, actors));
                } else {
                  return Optional.empty();
                }
              });
        });
  }

  private static EmailHeader convertRowToHeader(ResultSet rs, List<Actor> actors)
      throws SQLException {
    return new EmailHeader(
        rs.getInt(1),
        rs.getLong(2),
        actors,
        rs.getString(3),
        fromISOString(rs.getString(4)),
        rs.getLong(5),
        fromISOString(rs.getString(6)),
        rs.getLong(7),
        rs.getString(8),
        rs.getInt(9) == 1,
        false,
        rs.getInt(10) == 1);
  }

  private static Email convertRowToListEmail(ResultSet rs, List<Actor> actors) throws SQLException {
    return new Email(convertRowToHeader(rs, actors), false, null, Collections.emptyList());
  }

  private static Email convertRowToViewerEmail(ResultSet rs, List<Actor> actors)
      throws SQLException {
    return new Email(
        convertRowToHeader(rs, actors),
        rs.getInt(12) == 1,
        rs.getString(11),
        Collections.emptyList());
  }

  private List<Actor> getActors(int messageId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT type, name, email_address FROM actors WHERE message_id = ?",
        stmt -> {
          stmt.setInt(1, messageId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Actor> actors = new ArrayList<>();
                while (rs.next()) {
                  actors.add(
                      new Actor(
                          ActorType.fromValue(rs.getInt(1)),
                          rs.getString(3),
                          Optional.ofNullable(rs.getString(2))));
                }
                return actors;
              });
        });
  }

  @Override
  public void updateFlags(int id, boolean messageStarred, boolean messageRead) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE messages SET starred = ?, read = ? WHERE id = ?",
        stmt -> {
          stmt.setInt(1, messageStarred ? 1 : 0);
          stmt.setInt(2, messageRead ? 1 : 0);
          stmt.setInt(3, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public void updateRead(int id, boolean messageRead) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE messages SET read = ? WHERE id = ?",
        stmt -> {
          stmt.setInt(1, messageRead ? 1 : 0);
          stmt.setInt(2, id);
          stmt.executeUpdate();
        });
  }

  @Override
  public Set<Long> getAllMessageIds(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT imap_uid FROM messages WHERE folder_id = ?",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                HashSet<Long> messageIds = new HashSet<>();
                while (rs.next()) {
                  messageIds.add(rs.getLong(1));
                }
                return messageIds;
              });
        });
  }

  @Override
  public void removeAllByUid(Collection<Long> localMessageIds) {
    List<Long> batch = new ArrayList<>();
    for (Long messageId : localMessageIds) {
      batch.add(messageId);
      if (batch.size() >= IN_BATCH_SIZE) {
        removeBatchByUid(batch);
      }
    }
    if (!batch.isEmpty()) {
      removeBatchByUid(batch);
    }
  }

  @Override
  public int addMessage(int folderId, Email email) {
    return DbUtil.withConFunction(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            int messageId = insertMessage(con, folderId, email);
            con.commit();
            return messageId;
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  @Override
  public void addMessages(int folderId, List<Email> emails) {
    if (emails.isEmpty()) {
      return;
    }
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            for (Email email : emails) {
              insertMessage(con, folderId, email);
            }
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private int insertMessage(Connection con, int folderId, Email email) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO messages (folder_id, imap_uid, subject, sent_date, sent_date_epoch_s, received_date, received_date_epoch_s, body_excerpt, starred, read, body, plain_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            Statement.RETURN_GENERATED_KEYS)) {
      stmt.setInt(1, folderId);
      stmt.setLong(2, email.header().imapUid());
      stmt.setString(3, email.header().subject());
      stmt.setString(4, toISOString(email.header().sentDateTime()));
      stmt.setLong(5, email.header().sentDateTimeEpochSeconds());
      stmt.setString(6, toISOString(email.header().receivedDateTime()));
      stmt.setLong(7, email.header().receivedDateTimeEpochSeconds());
      stmt.setString(8, email.header().bodyExcerpt());
      stmt.setInt(9, email.header().starred() ? 1 : 0);
      stmt.setInt(10, email.header().read() ? 1 : 0);
      stmt.setString(11, email.body());
      stmt.setInt(12, email.plainText() ? 1 : 0);
      stmt.executeUpdate();
      try (ResultSet keyRs = stmt.getGeneratedKeys()) {
        if (!keyRs.next()) {
          throw new IllegalStateException(
              "We are expecting to get the generated keys when inserting a new message");
        }
        int messageId = keyRs.getInt(1);
        for (Actor actor : email.header().actors()) {
          saveActor(con, messageId, actor);
        }
        indexSearchDocument(con, messageId, email);
        return messageId;
      }
    }
  }

  private static void saveActor(Connection con, int messageId, Actor actor) throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO actors (message_id, type, name, email_address) VALUES (?, ?, ?, ?)
                """)) {
      stmt.setInt(1, messageId);
      stmt.setInt(2, actor.type().getValue());
      stmt.setString(3, actor.name().orElseGet(() -> null));
      stmt.setString(4, actor.emailAddress());
      stmt.executeUpdate();
    }
  }

  @Override
  public EmailPage getMessages(
      int folderId,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    String orderByString;
    String operatorString;
    if (order == SortOrder.DESCENDING) {
      orderByString = direction == PageDirection.RIGHT ? "DESC" : "ASC";
      operatorString = direction == PageDirection.RIGHT ? "<" : ">";
    } else {
      orderByString = direction == PageDirection.RIGHT ? "ASC" : "DESC";
      operatorString = direction == PageDirection.RIGHT ? ">" : "<";
    }
    // We do offset based paging. In order for this to work our sorting attribute needs to be
    // unique, so we add the message id as a second discriminator
    // if we would not do this, we would skip over all messages that have the same received date
    // which is not unlikely as the precision is only seconds
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT %s
                FROM messages
                WHERE folder_id = ? AND (received_date_epoch_s, id) %s (?, ?) ORDER BY received_date_epoch_s %s, id %s LIMIT ?
                """
            .formatted(MESSAGE_HEADER_COLUMNS, operatorString, orderByString, orderByString),
        stmt -> {
          stmt.setInt(1, folderId);
          stmt.setLong(2, dateTimeOffsetEpochSeconds);
          stmt.setInt(3, offsetMessageId);
          stmt.setInt(4, pageSize + 1);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Email> messages = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  // TODO: profile this and potentially optimise by directly joining? Or by
                  // gathering message ids and getting all actors in batch
                  List<Actor> actors = getActors(messageId);
                  messages.add(convertRowToListEmail(rs, actors));
                }
                boolean hasMoreResults = messages.size() > pageSize;
                if (hasMoreResults) {
                  messages.remove(messages.size() - 1);
                }
                if (direction == PageDirection.LEFT) {
                  Collections.reverse(messages);
                }
                // TODO: we are probably missing the case where we have an offset that is equal to
                // the first el
                boolean hasLeft =
                    switch (direction) {
                      case LEFT -> hasMoreResults;
                      case RIGHT ->
                          !((order == SortOrder.ASCENDING && dateTimeOffsetEpochSeconds == 0)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE));
                    };
                boolean hasRight =
                    switch (direction) {
                      case LEFT ->
                          !((order == SortOrder.ASCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == 0));
                      case RIGHT -> hasMoreResults;
                    };
                return new EmailPage(messages, hasLeft, hasRight, new PageParams(direction, order));
              });
        });
  }

  @Override
  public int getMessageCount(int accountId, int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT COUNT(*) FROM messages WHERE folder_id = ?",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting messages");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public EmailPage searchMessages(
      int accountId,
      String query,
      int pageSize,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      PageDirection direction,
      SortOrder order) {
    String orderByString;
    String operatorString;
    if (order == SortOrder.DESCENDING) {
      orderByString = direction == PageDirection.RIGHT ? "DESC" : "ASC";
      operatorString = direction == PageDirection.RIGHT ? "<" : ">";
    } else {
      orderByString = direction == PageDirection.RIGHT ? "ASC" : "DESC";
      operatorString = direction == PageDirection.RIGHT ? ">" : "<";
    }
    String matchQuery = SearchQueryUtils.toFtsMatchQuery(query);
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT %s
                FROM messages m
                JOIN message_search ON message_search.rowid = m.id
                JOIN folders f ON f.id = m.folder_id
                WHERE f.account_id = ?
                  AND message_search MATCH ?
                  AND (m.received_date_epoch_s, m.id) %s (?, ?)
                ORDER BY m.received_date_epoch_s %s, m.id %s
                LIMIT ?
                """
            .formatted(
                QUALIFIED_MESSAGE_HEADER_COLUMNS, operatorString, orderByString, orderByString),
        stmt -> {
          bindSearchParams(
              stmt,
              accountId,
              matchQuery,
              dateTimeOffsetEpochSeconds,
              offsetMessageId,
              pageSize + 1);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<Email> messages = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  messages.add(convertRowToListEmail(rs, actors));
                }
                boolean hasMoreResults = messages.size() > pageSize;
                if (hasMoreResults) {
                  messages.removeLast();
                }
                if (direction == PageDirection.LEFT) {
                  Collections.reverse(messages);
                }
                boolean hasLeft =
                    switch (direction) {
                      case LEFT -> hasMoreResults;
                      case RIGHT ->
                          !((order == SortOrder.ASCENDING && dateTimeOffsetEpochSeconds == 0)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE));
                    };
                boolean hasRight =
                    switch (direction) {
                      case LEFT ->
                          !((order == SortOrder.ASCENDING
                                  && dateTimeOffsetEpochSeconds == Long.MAX_VALUE)
                              || (order == SortOrder.DESCENDING
                                  && dateTimeOffsetEpochSeconds == 0));
                      case RIGHT -> hasMoreResults;
                    };
                return new EmailPage(messages, hasLeft, hasRight, new PageParams(direction, order));
              });
        });
  }

  @Override
  public int getSearchMessageCount(int accountId, String query) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
                SELECT COUNT(*)
                FROM messages m
                JOIN message_search ON message_search.rowid = m.id
                JOIN folders f ON f.id = m.folder_id
                WHERE f.account_id = ?
                  AND message_search MATCH ?
                """,
        stmt -> {
          bindSearchFilterParams(stmt, accountId, SearchQueryUtils.toFtsMatchQuery(query));
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting search results");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public void removeBatchByUid(List<Long> batch) {
    if (batch.size() > DbEmailRepository.IN_BATCH_SIZE) {
      throw new IllegalStateException(
          "Only allowed to delete batches of maximally %s messages"
              .formatted(DbEmailRepository.IN_BATCH_SIZE));
    }
    String inString = batch.stream().map(msgId -> "?").collect(Collectors.joining(", "));
    DbUtil.withConConsumer(
        ds,
        con -> {
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM message_search WHERE rowid IN (SELECT id FROM messages WHERE imap_uid IN (%s))"
                  .formatted(inString),
              stmt -> {
                for (int i = 0; i < batch.size(); i++) {
                  stmt.setLong(i + 1, batch.get(i));
                }
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM messages WHERE imap_uid IN (%s)".formatted(inString),
              stmt -> {
                for (int i = 0; i < batch.size(); i++) {
                  stmt.setLong(i + 1, batch.get(i));
                }
                stmt.executeUpdate();
              });
        });
  }

  @Override
  public void deleteById(int id) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            DbUtil.withPreparedStmtConsumer(
                con,
                "DELETE FROM message_search WHERE rowid = ?",
                stmt -> {
                  stmt.setInt(1, id);
                  stmt.executeUpdate();
                });
            DbUtil.withPreparedStmtConsumer(
                con,
                "DELETE FROM actors WHERE message_id = ?",
                stmt -> {
                  stmt.setInt(1, id);
                  stmt.executeUpdate();
                });
            DbUtil.withPreparedStmtConsumer(
                con,
                "DELETE FROM messages WHERE id = ?",
                stmt -> {
                  stmt.setInt(1, id);
                  stmt.executeUpdate();
                });
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private static String toISOString(ZonedDateTime dateTime) {
    return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static ZonedDateTime fromISOString(String isoDate) {
    return ZonedDateTime.parse(isoDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static void bindSearchFilterParams(
      java.sql.PreparedStatement stmt, int accountId, String matchQuery) throws SQLException {
    stmt.setInt(1, accountId);
    stmt.setString(2, matchQuery);
  }

  private static void bindSearchParams(
      java.sql.PreparedStatement stmt,
      int accountId,
      String matchQuery,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      int limit)
      throws SQLException {
    bindSearchFilterParams(stmt, accountId, matchQuery);
    stmt.setLong(3, dateTimeOffsetEpochSeconds);
    stmt.setInt(4, offsetMessageId);
    stmt.setInt(5, limit);
  }

  private static void indexSearchDocument(Connection con, int messageId, Email email)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            """
                INSERT INTO message_search(rowid, subject, body_excerpt, body, actors)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      stmt.setInt(1, messageId);
      stmt.setString(2, Optional.ofNullable(email.header().subject()).orElse(""));
      stmt.setString(3, Optional.ofNullable(email.header().bodyExcerpt()).orElse(""));
      stmt.setString(4, Optional.ofNullable(email.body()).orElse(""));
      stmt.setString(5, formatSearchActors(email.header().actors()));
      stmt.executeUpdate();
    }
  }

  private static String formatSearchActors(List<Actor> actors) {
    return actors.stream()
        .map(actor -> (actor.name().orElse("") + " " + actor.emailAddress()).trim())
        .filter(actor -> !actor.isBlank())
        .collect(Collectors.joining(" "));
  }
}
