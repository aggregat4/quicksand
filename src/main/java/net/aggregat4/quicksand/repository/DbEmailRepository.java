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
import net.aggregat4.quicksand.domain.SearchOrder;
import net.aggregat4.quicksand.domain.SortOrder;
import net.aggregat4.quicksand.search.SearchQueryUtils;
import net.aggregat4.quicksand.util.ContentHasher;

public class DbEmailRepository implements EmailRepository {
  private static final int IN_BATCH_SIZE = 100;

  /**
   * bm25 column weights for {@code message_search}, in FTS column order (subject, body_excerpt,
   * body, actors). Subject and actors rank above body content. See {@code specs/
   * search-prefix-and-relevance.md}.
   */
  private static final String BM25_WEIGHTS = "8.0, 2.0, 1.0, 5.0";

  private static final String MESSAGE_HEADER_COLUMNS =
      """
            id, imap_uid, subject, sent_date, sent_date_epoch_s, received_date, received_date_epoch_s, body_excerpt, starred, read
            """;
  private static final String QUALIFIED_MESSAGE_HEADER_COLUMNS =
      """
            m.id, m.imap_uid, m.subject, m.sent_date, m.sent_date_epoch_s, m.received_date, m.received_date_epoch_s, m.body_excerpt, m.starred, m.read
            """;
  private static final String MESSAGE_VIEWER_COLUMNS =
      MESSAGE_HEADER_COLUMNS + ", body, plain_text, body_content_hash, message_id_header";
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
    return DbUtil.withConFunction(
        ds,
        con ->
            DbUtil.withPreparedStmtFunction(
                con,
                "SELECT " + MESSAGE_VIEWER_COLUMNS + " FROM messages WHERE id = ?",
                stmt -> {
                  stmt.setInt(1, id);
                  return DbUtil.withResultSetFunction(
                      stmt,
                      rs -> {
                        if (!rs.next()) {
                          return Optional.empty();
                        }
                        List<Actor> actors = getActors(con, id);
                        return Optional.of(convertRowToViewerEmail(con, rs, actors, id));
                      });
                }));
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
  public Optional<Email> findByFolderAndUid(int folderId, long uid) {
    return findByRemoteIdentity(folderId, null, uid);
  }

  @Override
  public Optional<Email> findByRemoteKey(int folderId, long uidValidity, long uid) {
    return findByRemoteIdentity(folderId, uidValidity, uid);
  }

  private Optional<Email> findByRemoteIdentity(int folderId, Long uidValidity, long uid) {
    String uidValidityPredicate = uidValidity == null ? "" : "AND remote_uidvalidity = ?";
    return DbUtil.withConFunction(
        ds,
        con ->
            DbUtil.withPreparedStmtFunction(
                con,
                "SELECT "
                    + MESSAGE_HEADER_COLUMNS
                    + " FROM messages WHERE remote_folder_id = ? "
                    + uidValidityPredicate
                    + " AND remote_uid = ?",
                stmt -> {
                  int parameter = 1;
                  stmt.setInt(parameter++, folderId);
                  if (uidValidity != null) {
                    stmt.setLong(parameter++, uidValidity);
                  }
                  stmt.setLong(parameter, uid);
                  return DbUtil.withResultSetFunction(
                      stmt,
                      rs -> {
                        if (!rs.next()) {
                          return Optional.empty();
                        }
                        int messageId = rs.getInt(1);
                        List<Actor> actors = getActors(con, messageId);
                        return Optional.of(convertRowToListEmail(con, rs, actors, messageId));
                      });
                }));
  }

  private static EmailHeader convertRowToHeader(
      ResultSet rs, List<Actor> actors, boolean hasAttachment) throws SQLException {
    return CapturedMessageHeader.fromResultSet(rs).toHeader(actors, hasAttachment);
  }

  private record CapturedMessageHeader(
      int id,
      long imapUid,
      String subject,
      java.time.ZonedDateTime sentDate,
      long sentDateEpochSeconds,
      java.time.ZonedDateTime receivedDate,
      long receivedDateEpochSeconds,
      String bodyExcerpt,
      boolean starred,
      boolean read,
      java.util.Optional<Double> rank) {

    static CapturedMessageHeader fromResultSet(ResultSet rs) throws SQLException {
      return new CapturedMessageHeader(
          rs.getInt(1),
          rs.getLong(2),
          rs.getString(3),
          fromISOString(rs.getString(4)),
          rs.getLong(5),
          fromISOString(rs.getString(6)),
          rs.getLong(7),
          rs.getString(8),
          rs.getInt(9) == 1,
          rs.getInt(10) == 1,
          java.util.Optional.empty());
    }

    static CapturedMessageHeader fromResultSetWithRank(ResultSet rs) throws SQLException {
      return new CapturedMessageHeader(
          rs.getInt(1),
          rs.getLong(2),
          rs.getString(3),
          fromISOString(rs.getString(4)),
          rs.getLong(5),
          fromISOString(rs.getString(6)),
          rs.getLong(7),
          rs.getString(8),
          rs.getInt(9) == 1,
          rs.getInt(10) == 1,
          java.util.Optional.of(rs.getDouble(11)));
    }

    EmailHeader toHeader(List<Actor> actors, boolean hasAttachment) {
      return new EmailHeader(
          id,
          imapUid,
          actors,
          subject,
          sentDate,
          sentDateEpochSeconds,
          receivedDate,
          receivedDateEpochSeconds,
          bodyExcerpt,
          starred,
          hasAttachment,
          read);
    }

    Email toListEmail(List<Actor> actors, boolean hasAttachment) {
      return new Email(toHeader(actors, hasAttachment), false, null, List.of());
    }
  }

  private Email convertRowToListEmail(
      Connection con, ResultSet rs, List<Actor> actors, int messageId) throws SQLException {
    List<Attachment> attachments = attachmentRepository.findByMessageId(con, messageId);
    return new Email(
        convertRowToHeader(rs, actors, !attachments.isEmpty()), false, null, attachments);
  }

  private Email convertRowToViewerEmail(
      Connection con, ResultSet rs, List<Actor> actors, int messageId) throws SQLException {
    List<Attachment> attachments = attachmentRepository.findByMessageId(con, messageId);
    return new Email(
        convertRowToHeader(rs, actors, !attachments.isEmpty()),
        rs.getInt(12) == 1,
        rs.getString(11),
        attachments,
        List.of(),
        rs.getString(13),
        rs.getString(14));
  }

  private List<Actor> getActors(Connection con, int messageId) throws SQLException {
    return getActorsByMessageIds(con, List.of(messageId)).getOrDefault(messageId, List.of());
  }

  private Map<Integer, List<Actor>> getActorsByMessageIds(Connection con, List<Integer> messageIds)
      throws SQLException {
    if (messageIds.isEmpty()) {
      return Map.of();
    }
    Map<Integer, List<Actor>> actorsByMessageId = new HashMap<>();
    for (int offset = 0; offset < messageIds.size(); offset += IN_BATCH_SIZE) {
      List<Integer> batch =
          messageIds.subList(offset, Math.min(offset + IN_BATCH_SIZE, messageIds.size()));
      String placeholders = batch.stream().map(ignored -> "?").collect(Collectors.joining(","));
      DbUtil.withPreparedStmtConsumer(
          con,
          """
              SELECT type, name, email_address, message_id
              FROM actors
              WHERE message_id IN (%s)"""
              .formatted(placeholders),
          stmt -> {
            for (int i = 0; i < batch.size(); i++) {
              stmt.setInt(i + 1, batch.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
              while (rs.next()) {
                int messageId = rs.getInt(4);
                actorsByMessageId
                    .computeIfAbsent(messageId, ignored -> new ArrayList<>())
                    .add(
                        new Actor(
                            ActorType.fromValue(rs.getInt(1)),
                            rs.getString(3),
                            Optional.ofNullable(rs.getString(2))));
              }
            }
          });
    }
    return actorsByMessageId;
  }

  private Set<Integer> getMessageIdsWithAttachments(Connection con, List<Integer> messageIds)
      throws SQLException {
    if (messageIds.isEmpty()) {
      return Set.of();
    }
    Set<Integer> messageIdsWithAttachments = new HashSet<>();
    for (int offset = 0; offset < messageIds.size(); offset += IN_BATCH_SIZE) {
      List<Integer> batch =
          messageIds.subList(offset, Math.min(offset + IN_BATCH_SIZE, messageIds.size()));
      String placeholders = batch.stream().map(ignored -> "?").collect(Collectors.joining(","));
      DbUtil.withPreparedStmtConsumer(
          con,
          """
              SELECT DISTINCT message_id
              FROM attachments
              WHERE message_id IN (%s)"""
              .formatted(placeholders),
          stmt -> {
            for (int i = 0; i < batch.size(); i++) {
              stmt.setInt(i + 1, batch.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
              while (rs.next()) {
                messageIdsWithAttachments.add(rs.getInt(1));
              }
            }
          });
    }
    return messageIdsWithAttachments;
  }

  private List<Email> toListEmails(Connection con, List<CapturedMessageHeader> rows)
      throws SQLException {
    if (rows.isEmpty()) {
      return new ArrayList<>();
    }
    List<Integer> messageIds = rows.stream().map(CapturedMessageHeader::id).toList();
    Map<Integer, List<Actor>> actorsByMessageId = getActorsByMessageIds(con, messageIds);
    Set<Integer> messageIdsWithAttachments = getMessageIdsWithAttachments(con, messageIds);
    List<Email> emails = new ArrayList<>(rows.size());
    for (CapturedMessageHeader row : rows) {
      emails.add(
          row.toListEmail(
              actorsByMessageId.getOrDefault(row.id(), List.of()),
              messageIdsWithAttachments.contains(row.id())));
    }
    return emails;
  }

  private List<EmailHeader> toListHeaders(Connection con, List<CapturedMessageHeader> rows)
      throws SQLException {
    if (rows.isEmpty()) {
      return new ArrayList<>();
    }
    List<Integer> messageIds = rows.stream().map(CapturedMessageHeader::id).toList();
    Map<Integer, List<Actor>> actorsByMessageId = getActorsByMessageIds(con, messageIds);
    Set<Integer> messageIdsWithAttachments = getMessageIdsWithAttachments(con, messageIds);
    List<EmailHeader> headers = new ArrayList<>(rows.size());
    for (CapturedMessageHeader row : rows) {
      headers.add(
          row.toHeader(
              actorsByMessageId.getOrDefault(row.id(), List.of()),
              messageIdsWithAttachments.contains(row.id())));
    }
    return headers;
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
            if (context.get().read() == messageRead) {
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
        "SELECT remote_uid FROM messages WHERE remote_folder_id = ? AND remote_uid IS NOT NULL",
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
        "UPDATE messages SET imap_uid = ?, remote_uid = ? WHERE id = ?",
        stmt -> {
          stmt.setLong(1, imapUid);
          stmt.setLong(2, imapUid);
          stmt.setInt(3, messageId);
          stmt.executeUpdate();
        });
  }

  @Override
  public void removeAllByUid(int folderId, Collection<Long> imapUids) {
    List<Long> batch = new ArrayList<>();
    for (Long imapUid : imapUids) {
      batch.add(imapUid);
      if (batch.size() >= IN_BATCH_SIZE) {
        removeBatchByUid(folderId, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      removeBatchByUid(folderId, batch);
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
                INSERT INTO messages (
                  folder_id, imap_uid, subject, sent_date, sent_date_epoch_s,
                  received_date, received_date_epoch_s, body_excerpt, starred, read,
                  body, plain_text, body_content_hash,
                  remote_folder_id, remote_uidvalidity, remote_uid, message_id_header)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  (SELECT uidvalidity FROM folders WHERE id = ?), ?, ?)
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
      stmt.setString(13, ContentHasher.messageBodyContentHash(email.body()));
      stmt.setInt(14, folderId);
      stmt.setInt(15, folderId);
      stmt.setLong(16, email.header().imapUid());
      stmt.setString(17, normalizeMessageId(email.messageIdHeader()));
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

  private static String normalizeMessageId(String messageId) {
    if (messageId == null || messageId.isBlank()) {
      return null;
    }
    return messageId.trim();
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
    return DbUtil.withConFunction(
        ds,
        con ->
            DbUtil.withPreparedStmtFunction(
                con,
                """
                SELECT %s
                FROM messages
                WHERE folder_id = ? AND (received_date_epoch_s, id) %s (?, ?) ORDER BY received_date_epoch_s %s, id %s LIMIT ?
                """
                    .formatted(
                        MESSAGE_HEADER_COLUMNS, operatorString, orderByString, orderByString),
                stmt -> {
                  stmt.setInt(1, folderId);
                  stmt.setLong(2, dateTimeOffsetEpochSeconds);
                  stmt.setInt(3, offsetMessageId);
                  stmt.setInt(4, pageSize + 1);
                  return DbUtil.withResultSetFunction(
                      stmt,
                      rs -> {
                        List<CapturedMessageHeader> rows = new ArrayList<>();
                        while (rs.next()) {
                          rows.add(CapturedMessageHeader.fromResultSet(rs));
                        }
                        List<Email> messages = toListEmails(con, rows);
                        boolean hasMoreResults = messages.size() > pageSize;
                        if (hasMoreResults) {
                          messages.remove(messages.size() - 1);
                        }
                        if (direction == PageDirection.LEFT) {
                          Collections.reverse(messages);
                        }
                        // TODO: we are probably missing the case where we have an offset that is
                        // equal to
                        // the first el
                        boolean hasLeft =
                            switch (direction) {
                              case LEFT -> hasMoreResults;
                              case RIGHT ->
                                  !((order == SortOrder.ASCENDING
                                          && dateTimeOffsetEpochSeconds == 0)
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
                        return new EmailPage(
                            messages, hasLeft, hasRight, new PageParams(direction, order));
                      });
                }));
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
    return DbUtil.withConFunction(
        ds,
        con ->
            DbUtil.withPreparedStmtFunction(
                con,
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
                        List<CapturedMessageHeader> rows = new ArrayList<>();
                        while (rs.next()) {
                          rows.add(CapturedMessageHeader.fromResultSet(rs));
                        }
                        return toListHeaders(con, rows);
                      });
                }));
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
      SearchOrder order,
      PageDirection direction,
      Optional<Double> rankOffset,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      boolean endJump) {
    String matchQuery = SearchQueryUtils.toFtsMatchQuery(query);
    return DbUtil.withConFunction(
        ds,
        con ->
            order == SearchOrder.BEST_MATCH
                ? searchMessagesByRank(
                    con,
                    accountId,
                    matchQuery,
                    pageSize,
                    direction,
                    rankOffset,
                    dateTimeOffsetEpochSeconds,
                    offsetMessageId,
                    endJump)
                : searchMessagesByDate(
                    con,
                    accountId,
                    matchQuery,
                    pageSize,
                    direction,
                    order.toSortOrder(),
                    dateTimeOffsetEpochSeconds,
                    offsetMessageId));
  }

  private EmailPage searchMessagesByDate(
      Connection con,
      int accountId,
      String matchQuery,
      int pageSize,
      PageDirection direction,
      SortOrder order,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId)
      throws SQLException {
    String orderByString;
    String operatorString;
    if (order == SortOrder.DESCENDING) {
      orderByString = direction == PageDirection.RIGHT ? "DESC" : "ASC";
      operatorString = direction == PageDirection.RIGHT ? "<" : ">";
    } else {
      orderByString = direction == PageDirection.RIGHT ? "ASC" : "DESC";
      operatorString = direction == PageDirection.RIGHT ? ">" : "<";
    }
    return DbUtil.withPreparedStmtFunction(
        con,
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
                List<CapturedMessageHeader> rows = new ArrayList<>();
                while (rs.next()) {
                  rows.add(CapturedMessageHeader.fromResultSet(rs));
                }
                boolean hasMoreResults = rows.size() > pageSize;
                if (hasMoreResults) {
                  rows.removeLast();
                }
                if (direction == PageDirection.LEFT) {
                  Collections.reverse(rows);
                }
                List<Email> messages = toListEmails(con, rows);
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
                return new EmailPage(
                    messages,
                    hasLeft,
                    hasRight,
                    new PageParams(direction, order),
                    Optional.empty(),
                    Optional.empty());
              });
        });
  }

  /**
   * Relevance-ordered search. Ordering is {@code bm25(message_search, ...) ASC,
   * received_date_epoch_s DESC, id DESC}; the rank cursor (rankOffset, received date, id) keeps
   * pagination deterministic for an unchanged index and avoids duplicate rows within a response.
   *
   * <p>RIGHT fetches rows after the cursor (more relevant → less relevant); LEFT fetches rows
   * before the cursor in reverse and flips them. End-jump fetches the least-relevant terminal page
   * in reversed order.
   */
  private EmailPage searchMessagesByRank(
      Connection con,
      int accountId,
      String matchQuery,
      int pageSize,
      PageDirection direction,
      Optional<Double> rankOffset,
      long dateTimeOffsetEpochSeconds,
      int offsetMessageId,
      boolean endJump)
      throws SQLException {
    String rankExpr = "bm25(message_search, %s)".formatted(BM25_WEIGHTS);
    boolean reverse = endJump || direction == PageDirection.LEFT;
    String rankDir = reverse ? "DESC" : "ASC";
    String dateDir = reverse ? "ASC" : "DESC";
    String idDir = reverse ? "ASC" : "DESC";
    String cursorPredicate;
    if (endJump) {
      // No cursor: start from the least-relevant end.
      cursorPredicate = "";
    } else if (rankOffset.isEmpty()) {
      cursorPredicate = "";
    } else {
      // Ordering is (rank ASC, date DESC, id DESC). "After" the cursor (RIGHT, forward) uses
      // rank >, date <, id <; "before" the cursor (LEFT, backward) uses rank <, date >, id >.
      // LEFT fetches the before-set in reversed order and flips it in Java.
      boolean forward = direction == PageDirection.RIGHT;
      String rankOp = forward ? ">" : "<";
      String dateOp = forward ? "<" : ">";
      String idOp = forward ? "<" : ">";
      cursorPredicate =
          """
          AND (%s %s ?
               OR (%s = ? AND m.received_date_epoch_s %s ?)
               OR (%s = ? AND m.received_date_epoch_s = ? AND m.id %s ?))
          """
              .formatted(rankExpr, rankOp, rankExpr, dateOp, rankExpr, idOp);
    }
    String sql =
        """
        SELECT %s, %s AS rank
        FROM messages m
        JOIN message_search ON message_search.rowid = m.id
        JOIN folders f ON f.id = m.folder_id
        WHERE f.account_id = ?
          AND message_search MATCH ?
          %s
        ORDER BY %s %s, m.received_date_epoch_s %s, m.id %s
        LIMIT ?
        """
            .formatted(
                QUALIFIED_MESSAGE_HEADER_COLUMNS,
                rankExpr,
                cursorPredicate,
                rankExpr,
                rankDir,
                dateDir,
                idDir);
    return DbUtil.withPreparedStmtFunction(
        con,
        sql,
        stmt -> {
          int param = 1;
          stmt.setInt(param++, accountId);
          stmt.setString(param++, matchQuery);
          if (!endJump && rankOffset.isPresent()) {
            double rank = rankOffset.get();
            stmt.setDouble(param++, rank);
            stmt.setDouble(param++, rank);
            stmt.setLong(param++, dateTimeOffsetEpochSeconds);
            stmt.setDouble(param++, rank);
            stmt.setLong(param++, dateTimeOffsetEpochSeconds);
            stmt.setInt(param++, offsetMessageId);
          }
          stmt.setInt(param, pageSize + 1);
          return DbUtil.withResultSetFunction(
              stmt,
              rs -> {
                List<CapturedMessageHeader> rows = new ArrayList<>();
                while (rs.next()) {
                  rows.add(CapturedMessageHeader.fromResultSetWithRank(rs));
                }
                boolean hasMoreResults = rows.size() > pageSize;
                if (hasMoreResults) {
                  rows.removeLast();
                }
                // The reversed fetch already produced rows in display order's reverse; flip to
                // display order (most relevant first within the page).
                if (reverse) {
                  Collections.reverse(rows);
                }
                List<Email> messages = toListEmails(con, rows);
                Optional<Double> firstRank =
                    rows.isEmpty() ? Optional.empty() : rows.getFirst().rank();
                Optional<Double> lastRank =
                    rows.isEmpty() ? Optional.empty() : rows.getLast().rank();
                boolean hasLeft;
                boolean hasRight;
                if (endJump) {
                  hasLeft = hasMoreResults;
                  hasRight = false;
                } else if (direction == PageDirection.RIGHT) {
                  hasLeft = rankOffset.isPresent();
                  hasRight = hasMoreResults;
                } else {
                  hasLeft = hasMoreResults;
                  hasRight = rankOffset.isPresent();
                }
                return new EmailPage(
                    messages,
                    hasLeft,
                    hasRight,
                    new PageParams(direction, SearchOrder.BEST_MATCH.toSortOrder()),
                    firstRank,
                    lastRank);
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

  /**
   * Removes a batch of mirrored messages from one folder by IMAP UID.
   *
   * <p>IMAP UIDs are only unique within a folder, so every statement scopes to {@code folderId}.
   *
   * <p>Deletion must follow this order because several tables reference {@code messages(id)}
   * without {@code ON DELETE CASCADE} (see {@code QuicksandMigrations}):
   *
   * <ol>
   *   <li>{@code mailbox_action_queue} rows keyed by {@code message_id}
   *   <li>{@code drafts.source_message_id} and {@code outbound_messages.source_message_id} cleared
   *       (reply/forward metadata should survive mirror eviction)
   *   <li>{@code message_search} FTS rows (virtual table, not FK-managed)
   *   <li>{@code messages} rows ({@code actors} and {@code attachments} cascade from here)
   * </ol>
   *
   * <p>UIDVALIDITY changes and expunge reconciliation both call through here when evicting local
   * mirror state.
   */
  @Override
  public void removeBatchByUid(int folderId, List<Long> batch) {
    if (batch.isEmpty()) {
      return;
    }
    if (batch.size() > DbEmailRepository.IN_BATCH_SIZE) {
      throw new IllegalStateException(
          "Only allowed to delete batches of maximally %s messages"
              .formatted(DbEmailRepository.IN_BATCH_SIZE));
    }
    String inString = batch.stream().map(msgId -> "?").collect(Collectors.joining(", "));
    String folderScopedMessageIds =
        "SELECT id FROM messages WHERE remote_folder_id = ? AND remote_uid IN (%s)"
            .formatted(inString);
    DbUtil.withConConsumer(
        ds,
        con -> {
          DbUtil.withPreparedStmtConsumer(
              con,
              "UPDATE mailbox_action_queue SET message_id = NULL WHERE message_id IN (%s)"
                  .formatted(folderScopedMessageIds),
              stmt -> {
                bindFolderUidBatch(stmt, folderId, batch);
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "UPDATE drafts SET source_message_id = NULL WHERE source_message_id IN (%s)"
                  .formatted(folderScopedMessageIds),
              stmt -> {
                bindFolderUidBatch(stmt, folderId, batch);
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "UPDATE outbound_messages SET source_message_id = NULL WHERE source_message_id IN (%s)"
                  .formatted(folderScopedMessageIds),
              stmt -> {
                bindFolderUidBatch(stmt, folderId, batch);
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM message_search WHERE rowid IN (%s)".formatted(folderScopedMessageIds),
              stmt -> {
                bindFolderUidBatch(stmt, folderId, batch);
                stmt.executeUpdate();
              });
          DbUtil.withPreparedStmtConsumer(
              con,
              "DELETE FROM messages WHERE remote_folder_id = ? AND remote_uid IN (%s)"
                  .formatted(inString),
              stmt -> {
                bindFolderUidBatch(stmt, folderId, batch);
                stmt.executeUpdate();
              });
        });
  }

  private static void bindFolderUidBatch(PreparedStatement stmt, int folderId, List<Long> batch)
      throws SQLException {
    stmt.setInt(1, folderId);
    for (int i = 0; i < batch.size(); i++) {
      stmt.setLong(i + 2, batch.get(i));
    }
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
  public void resolveMoveLikeSourceUidsAbsentFromRemote(
      int accountId, String sourceRemoteName, Long sourceUidValidity, Set<Long> remoteUidsPresent) {
    mailboxActions.resolveMoveLikeSourceUidsAbsentFromRemote(
        accountId, sourceRemoteName, sourceUidValidity, remoteUidsPresent);
  }

  @Override
  public void resolveMoveLikeSourceUidsVanished(
      int accountId, String sourceRemoteName, Long sourceUidValidity, Set<Long> vanishedUids) {
    mailboxActions.resolveMoveLikeSourceUidsVanished(
        accountId, sourceRemoteName, sourceUidValidity, vanishedUids);
  }

  @Override
  public void markMoveLikeActionsConflictForUidValidityChange(
      int accountId, int folderId, long newUidValidity, ZonedDateTime now) {
    mailboxActions.markMoveLikeActionsConflictForUidValidityChange(
        accountId, folderId, newUidValidity, now);
  }

  @Override
  public Set<Long> getPendingReadStateActionSourceUids(
      int accountId, String sourceRemoteName, Long sourceUidValidity) {
    return mailboxActions.getPendingReadStateActionSourceUids(
        accountId, sourceRemoteName, sourceUidValidity);
  }

  @Override
  public MailboxSyncStatus getMailboxSyncStatus(int accountId) {
    return mailboxActions.getMailboxSyncStatus(accountId);
  }

  @Override
  public boolean needsMailboxSyncAttention(int accountId) {
    return mailboxActions.needsMailboxSyncAttention(accountId);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueMailboxActions(ZonedDateTime now, int limit) {
    return mailboxActions.claimDueMailboxActions(now, limit);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueMailboxActions(
      int accountId, ZonedDateTime now, int limit) {
    return mailboxActions.claimDueMailboxActions(accountId, now, limit);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueReadStateActions(ZonedDateTime now, int limit) {
    return mailboxActions.claimDueReadStateActions(now, limit);
  }

  @Override
  public List<MailboxActionQueueRow> claimDueReadStateActions(
      int accountId, ZonedDateTime now, int limit) {
    return mailboxActions.claimDueReadStateActions(accountId, now, limit);
  }

  @Override
  public void markMailboxActionSucceeded(int id, ZonedDateTime now) {
    mailboxActions.markMailboxActionSucceeded(id, now);
  }

  @Override
  public void markMailboxActionAttemptedUnknown(int id, ZonedDateTime now) {
    mailboxActions.markMailboxActionAttemptedUnknown(id, now);
  }

  @Override
  public void confirmMailboxMoveApplied(
      int actionId,
      int messageId,
      Integer targetFolderId,
      long targetUidValidity,
      long targetUid,
      ZonedDateTime now) {
    mailboxActions.confirmMailboxMoveApplied(
        actionId, messageId, targetFolderId, targetUidValidity, targetUid, now);
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
