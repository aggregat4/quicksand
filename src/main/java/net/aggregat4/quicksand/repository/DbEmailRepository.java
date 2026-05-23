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
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;
import net.aggregat4.quicksand.domain.EmailPage;
import net.aggregat4.quicksand.domain.FolderSpecialUse;
import net.aggregat4.quicksand.domain.MailboxActionQueueRow;
import net.aggregat4.quicksand.domain.MailboxActionResolutionType;
import net.aggregat4.quicksand.domain.MailboxActionType;
import net.aggregat4.quicksand.domain.MailboxSyncStatus;
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
  private final AttachmentRepository attachmentRepository;
  private final DbMailboxActionRepository mailboxActions;

  public DbEmailRepository(DataSource ds, AttachmentRepository attachmentRepository) {
    this.ds = ds;
    this.attachmentRepository = attachmentRepository;
    this.mailboxActions = new DbMailboxActionRepository(ds);
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
                return Optional.of(convertRowToViewerEmail(rs, actors, id));
              });
        });
  }

  @Override
  public Optional<Integer> findAccountIdByMessageId(int id) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        "SELECT f.account_id FROM messages m JOIN folders f ON m.folder_id = f.id WHERE m.id = ?",
        stmt -> {
          stmt.setInt(1, id);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  return Optional.empty();
                }
                return Optional.of(rs.getInt(1));
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
                  return Optional.of(convertRowToListEmail(rs, actors, messageId));
                } else {
                  return Optional.empty();
                }
              });
        });
  }

  private static EmailHeader convertRowToHeader(
      ResultSet rs, List<Actor> actors, boolean hasAttachment) throws SQLException {
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
        hasAttachment,
        rs.getInt(10) == 1);
  }

  private Email convertRowToListEmail(ResultSet rs, List<Actor> actors, int messageId)
      throws SQLException {
    List<Attachment> attachments = attachmentRepository.findByMessageId(messageId);
    return new Email(
        convertRowToHeader(rs, actors, !attachments.isEmpty()), false, null, attachments);
  }

  private Email convertRowToViewerEmail(ResultSet rs, List<Actor> actors, int messageId)
      throws SQLException {
    List<Attachment> attachments = attachmentRepository.findByMessageId(messageId);
    return new Email(
        convertRowToHeader(rs, actors, !attachments.isEmpty()),
        rs.getInt(12) == 1,
        rs.getString(11),
        attachments);
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
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<MailboxActionDbSupport.MessageActionContext> context =
                MailboxActionDbSupport.findMessageActionContext(con, id);
            if (context.isEmpty()) {
              con.rollback();
              return;
            }
            DbUtil.withPreparedStmtConsumer(
                con,
                "UPDATE messages SET read = ? WHERE id = ?",
                stmt -> {
                  stmt.setInt(1, messageRead ? 1 : 0);
                  stmt.setInt(2, id);
                  stmt.executeUpdate();
                });
            MailboxActionDbSupport.enqueueAction(
                con,
                context.get(),
                messageRead ? MailboxActionType.MARK_READ : MailboxActionType.MARK_UNREAD,
                null,
                null,
                null);
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
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
                Set<Long> messageIds = new HashSet<>();
                while (rs.next()) {
                  messageIds.add(rs.getLong(1));
                }
                return Set.copyOf(messageIds);
              });
        });
  }

  @Override
  public void updateMessageImapUid(int messageId, long imapUid) {
    DbUtil.withPreparedStmtConsumer(
        ds,
        "UPDATE messages SET imap_uid = ? WHERE id = ?",
        stmt -> {
          stmt.setLong(1, imapUid);
          stmt.setInt(2, messageId);
          stmt.executeUpdate();
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
        attachmentRepository.saveMessageAttachments(con, messageId, email.inboundAttachments());
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
                  messages.add(convertRowToListEmail(rs, actors, messageId));
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
  public Map<Integer, Integer> countUnreadByFolder(int accountId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT m.folder_id, COUNT(*)
            FROM messages m
            JOIN folders f ON f.id = m.folder_id
            WHERE f.account_id = ? AND m.read = 0
            GROUP BY m.folder_id""",
        stmt -> {
          stmt.setInt(1, accountId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                Map<Integer, Integer> counts = new HashMap<>();
                while (rs.next()) {
                  counts.put(rs.getInt(1), rs.getInt(2));
                }
                return counts;
              });
        });
  }

  @Override
  public int countNewSinceLastView(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT COUNT(*)
            FROM messages m
            JOIN folders f ON f.id = m.folder_id
            WHERE m.folder_id = ?
              AND m.received_date_epoch_s > COALESCE(f.last_viewed_epoch_s, 0)""",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when counting new messages");
                }
                return rs.getInt(1);
              });
        });
  }

  @Override
  public long maxReceivedEpochSeconds(int folderId) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT COALESCE(MAX(received_date_epoch_s), 0)
            FROM messages
            WHERE folder_id = ?""",
        stmt -> {
          stmt.setInt(1, folderId);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                if (!rs.next()) {
                  throw new IllegalStateException(
                      "We are expecting to get a result when reading max received date");
                }
                return rs.getLong(1);
              });
        });
  }

  @Override
  public List<EmailHeader> getMessagesNewerThan(
      int folderId, long afterReceivedEpochSeconds, int afterMessageId, int limit) {
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT %s
            FROM messages
            WHERE folder_id = ?
              AND (received_date_epoch_s, id) > (?, ?)
            ORDER BY received_date_epoch_s DESC, id DESC
            LIMIT ?"""
            .formatted(MESSAGE_HEADER_COLUMNS),
        stmt -> {
          stmt.setInt(1, folderId);
          stmt.setLong(2, afterReceivedEpochSeconds);
          stmt.setInt(3, afterMessageId);
          stmt.setInt(4, limit);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<EmailHeader> headers = new ArrayList<>();
                while (rs.next()) {
                  int messageId = rs.getInt(1);
                  List<Actor> actors = getActors(messageId);
                  boolean hasAttachment =
                      !attachmentRepository.findByMessageId(messageId).isEmpty();
                  headers.add(convertRowToHeader(rs, actors, hasAttachment));
                }
                return headers;
              });
        });
  }

  @Override
  public Map<Integer, Boolean> getReadFlagsByMessageIds(
      int accountId, Collection<Integer> messageIds) {
    if (messageIds.isEmpty()) {
      return Map.of();
    }
    List<Integer> ids = messageIds.stream().distinct().toList();
    String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
    return DbUtil.withPreparedStmtFunction(
        ds,
        """
            SELECT m.id, m.read
            FROM messages m
            JOIN folders f ON f.id = m.folder_id
            WHERE f.account_id = ? AND m.id IN (%s)"""
            .formatted(placeholders),
        stmt -> {
          stmt.setInt(1, accountId);
          for (int i = 0; i < ids.size(); i++) {
            stmt.setInt(i + 2, ids.get(i));
          }
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                Map<Integer, Boolean> readFlags = new HashMap<>();
                while (rs.next()) {
                  readFlags.put(rs.getInt(1), rs.getInt(2) == 1);
                }
                return readFlags;
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
                  messages.add(convertRowToListEmail(rs, actors, messageId));
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
    moveToMappedSpecialFolderById(id, MailboxActionType.DELETE, FolderSpecialUse.TRASH);
  }

  @Override
  public void archiveById(int id) {
    moveToMappedSpecialFolderById(id, MailboxActionType.ARCHIVE, FolderSpecialUse.ARCHIVE);
  }

  @Override
  public void markSpamById(int id) {
    moveToMappedSpecialFolderById(id, MailboxActionType.MARK_SPAM, FolderSpecialUse.JUNK);
  }

  @Override
  public void moveToFolderById(int id, int targetFolderId) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<Integer> messageAccountId =
                MailboxActionDbSupport.findMessageAccountId(con, id);
            Optional<Integer> targetAccountId =
                MailboxActionDbSupport.findFolderAccountId(con, targetFolderId);
            if (messageAccountId.isEmpty()
                || targetAccountId.isEmpty()
                || !messageAccountId.get().equals(targetAccountId.get())) {
              con.rollback();
              return;
            }

            Optional<MailboxActionDbSupport.MessageActionContext> context =
                MailboxActionDbSupport.findMessageActionContext(con, id);
            Optional<MailboxActionDbSupport.TargetFolderContext> target =
                MailboxActionDbSupport.findTargetFolderContext(con, targetFolderId);
            if (context.isEmpty() || target.isEmpty()) {
              con.rollback();
              return;
            }

            MailboxActionDbSupport.moveMessageToFolder(con, id, targetFolderId);
            MailboxActionDbSupport.enqueueAction(
                con, context.get(), MailboxActionType.MOVE, target.get(), null, null);
            con.commit();
          } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
          } finally {
            con.setAutoCommit(previousAutoCommit);
          }
        });
  }

  private void moveToMappedSpecialFolderById(
      int id, MailboxActionType actionType, FolderSpecialUse specialUse) {
    DbUtil.withConConsumer(
        ds,
        con -> {
          boolean previousAutoCommit = con.getAutoCommit();
          con.setAutoCommit(false);
          try {
            Optional<MailboxActionDbSupport.MessageActionContext> context =
                MailboxActionDbSupport.findMessageActionContext(con, id);
            if (context.isEmpty()) {
              con.rollback();
              return;
            }
            Optional<MailboxActionDbSupport.TargetFolderContext> target =
                MailboxActionDbSupport.findMappedTargetFolderContext(
                    con, context.get().accountId(), specialUse);
            if (target.isEmpty()) {
              con.rollback();
              return;
            }
            MailboxActionDbSupport.moveMessageToFolder(con, id, target.get().folderId());
            MailboxActionDbSupport.enqueueAction(
                con, context.get(), actionType, target.get(), specialUse, null);
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

  @Override
  public Set<Long> getPendingMoveLikeActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity) {
    return mailboxActions.getPendingMoveLikeActionSourceUids(
        accountId, sourceRemoteName, sourceUidValidity);
  }

  @Override
  public MailboxSyncStatus getMailboxSyncStatus(int accountId) {
    return mailboxActions.getMailboxSyncStatus(accountId);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit) {
    return mailboxActions.claimDueMailboxActions(now, limit);
  }

  @Override
  public void markMailboxActionSucceeded(int id, ZonedDateTime now) {
    mailboxActions.markMailboxActionSucceeded(id, now);
  }

  @Override
  public void markMailboxActionRetry(
      int id, String error, ZonedDateTime nextAttempt, ZonedDateTime now) {
    mailboxActions.markMailboxActionRetry(id, error, nextAttempt, now);
  }

  @Override
  public void markMailboxActionConflict(int id, String error, ZonedDateTime now) {
    mailboxActions.markMailboxActionConflict(id, error, now);
  }

  @Override
  public void markMailboxActionPermanentFailure(int id, String error, ZonedDateTime now) {
    mailboxActions.markMailboxActionPermanentFailure(id, error, now);
  }

  @Override
  public Optional<MailboxActionQueueRow> findMailboxAction(int actionId, int accountId) {
    return mailboxActions.findMailboxAction(actionId, accountId);
  }

  @Override
  public boolean requestMailboxActionRetry(int actionId, int accountId, ZonedDateTime now) {
    return mailboxActions.requestMailboxActionRetry(actionId, accountId, now);
  }

  @Override
  public boolean dismissMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return mailboxActions.dismissMailboxAction(actionId, accountId, now);
  }

  @Override
  public boolean abandonMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return mailboxActions.abandonMailboxAction(actionId, accountId, now);
  }

  @Override
  public boolean rollbackMailboxAction(int actionId, int accountId, ZonedDateTime now) {
    return mailboxActions.rollbackMailboxAction(actionId, accountId, now);
  }

  @Override
  public void resolveUnresolvedMailboxActions(
      int accountId, MailboxActionResolutionType resolutionType, ZonedDateTime now) {
    mailboxActions.resolveUnresolvedMailboxActions(accountId, resolutionType, now);
  }

  @Override
  public void clearMirroredMailboxState(int accountId) {
    mailboxActions.clearMirroredMailboxState(accountId);
  }

  @Override
  public int purgeStaleMailboxActionRows(ZonedDateTime now) {
    return mailboxActions.purgeStaleMailboxActionRows(now);
  }

  @Override
  public void enqueueAppendSent(int outboundMessageId) {
    mailboxActions.enqueueAppendSent(outboundMessageId);
  }

  @Override
  public void scheduleDraftUpsert(int draftId, ZonedDateTime nextAttemptAt) {
    mailboxActions.scheduleDraftUpsert(draftId, nextAttemptAt);
  }

  @Override
  public void enqueueDraftDelete(int draftId) {
    mailboxActions.enqueueDraftDelete(draftId);
  }

  @Override
  public void enqueueDraftDelete(Connection con, int draftId) {
    mailboxActions.enqueueDraftDelete(con, draftId);
  }
}
