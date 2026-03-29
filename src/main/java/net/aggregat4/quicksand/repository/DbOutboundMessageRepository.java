package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.OutboundMessage;
import net.aggregat4.quicksand.domain.OutboundMessageStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DbOutboundMessageRepository implements OutboundMessageRepository {
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final DataSource ds;

    public DbOutboundMessageRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public OutboundMessage create(Connection con, OutboundMessage outboundMessage) {
        return DbUtil.withPreparedStmtFunction(con, """
                INSERT INTO outbound_messages (
                    account_id, source_message_id, from_name, from_address,
                    to_recipients, cc_recipients, bcc_recipients, subject, body,
                    status, attempt_count, last_error, queued_at, next_attempt_at, next_attempt_at_epoch_s, sent_at, sent_at_epoch_s, queued_at_epoch_s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stmt -> {
            stmt.setInt(1, outboundMessage.accountId());
            if (outboundMessage.sourceMessageId().isPresent()) {
                stmt.setInt(2, outboundMessage.sourceMessageId().get());
            } else {
                stmt.setNull(2, java.sql.Types.INTEGER);
            }
            stmt.setString(3, outboundMessage.fromName());
            stmt.setString(4, outboundMessage.fromAddress());
            stmt.setString(5, outboundMessage.to());
            stmt.setString(6, outboundMessage.cc());
            stmt.setString(7, outboundMessage.bcc());
            stmt.setString(8, outboundMessage.subject());
            stmt.setString(9, outboundMessage.body());
            stmt.setString(10, outboundMessage.status().name());
            stmt.setInt(11, outboundMessage.attemptCount());
            stmt.setString(12, outboundMessage.lastError().orElse(null));
            stmt.setString(13, ISO_DATE_TIME.format(outboundMessage.queuedAt()));
            if (outboundMessage.nextAttemptAt().isPresent()) {
                stmt.setString(14, ISO_DATE_TIME.format(outboundMessage.nextAttemptAt().get()));
                stmt.setLong(15, outboundMessage.nextAttemptAt().get().toEpochSecond());
            } else {
                stmt.setNull(14, Types.VARCHAR);
                stmt.setNull(15, Types.BIGINT);
            }
            if (outboundMessage.sentAt().isPresent()) {
                stmt.setString(16, ISO_DATE_TIME.format(outboundMessage.sentAt().get()));
                stmt.setLong(17, outboundMessage.sentAt().get().toEpochSecond());
            } else {
                stmt.setNull(16, Types.VARCHAR);
                stmt.setNull(17, Types.BIGINT);
            }
            stmt.setLong(18, outboundMessage.queuedAtEpochSeconds());
            stmt.executeUpdate();
            try (ResultSet keyRs = stmt.getGeneratedKeys()) {
                if (!keyRs.next()) {
                    throw new IllegalStateException("Expected generated key when creating outbound message");
                }
                return new OutboundMessage(
                        keyRs.getInt(1),
                        outboundMessage.accountId(),
                        outboundMessage.sourceMessageId(),
                        outboundMessage.fromName(),
                        outboundMessage.fromAddress(),
                        outboundMessage.to(),
                        outboundMessage.cc(),
                        outboundMessage.bcc(),
                        outboundMessage.subject(),
                        outboundMessage.body(),
                        outboundMessage.status(),
                        outboundMessage.attemptCount(),
                        outboundMessage.lastError(),
                        outboundMessage.queuedAt(),
                        outboundMessage.nextAttemptAt(),
                        outboundMessage.sentAt(),
                        outboundMessage.queuedAtEpochSeconds());
            }
        });
    }

    @Override
    public Optional<OutboundMessage> findById(int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, source_message_id, from_name, from_address,
                       to_recipients, cc_recipients, bcc_recipients, subject, body,
                       status, attempt_count, last_error, queued_at, next_attempt_at, next_attempt_at_epoch_s, sent_at, sent_at_epoch_s, queued_at_epoch_s
                FROM outbound_messages
                WHERE id = ?
                """, stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> rs.next() ? Optional.of(toOutboundMessage(rs)) : Optional.empty());
        });
    }

    @Override
    public List<OutboundMessage> findByAccountId(int accountId) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, source_message_id, from_name, from_address,
                       to_recipients, cc_recipients, bcc_recipients, subject, body,
                       status, attempt_count, last_error, queued_at, next_attempt_at, next_attempt_at_epoch_s, sent_at, sent_at_epoch_s, queued_at_epoch_s
                FROM outbound_messages
                WHERE account_id = ?
                ORDER BY queued_at_epoch_s DESC, id DESC
                """, stmt -> {
            stmt.setInt(1, accountId);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<OutboundMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(toOutboundMessage(rs));
                }
                return messages;
            });
        });
    }

    @Override
    public List<OutboundMessage> findByStatus(OutboundMessageStatus status) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, source_message_id, from_name, from_address,
                       to_recipients, cc_recipients, bcc_recipients, subject, body,
                       status, attempt_count, last_error, queued_at, next_attempt_at, next_attempt_at_epoch_s, sent_at, sent_at_epoch_s, queued_at_epoch_s
                FROM outbound_messages
                WHERE status = ?
                ORDER BY queued_at_epoch_s ASC, id ASC
                """, stmt -> {
            stmt.setString(1, status.name());
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<OutboundMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(toOutboundMessage(rs));
                }
                return messages;
            });
        });
    }

    @Override
    public List<OutboundMessage> findDeliverable(ZonedDateTime now) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, source_message_id, from_name, from_address,
                       to_recipients, cc_recipients, bcc_recipients, subject, body,
                       status, attempt_count, last_error, queued_at, next_attempt_at, next_attempt_at_epoch_s, sent_at, sent_at_epoch_s, queued_at_epoch_s
                FROM outbound_messages
                WHERE status = ? AND COALESCE(next_attempt_at_epoch_s, queued_at_epoch_s) <= ?
                ORDER BY COALESCE(next_attempt_at_epoch_s, queued_at_epoch_s) ASC, id ASC
                """, stmt -> {
            stmt.setString(1, OutboundMessageStatus.QUEUED.name());
            stmt.setLong(2, now.toEpochSecond());
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<OutboundMessage> messages = new ArrayList<>();
                while (rs.next()) {
                    messages.add(toOutboundMessage(rs));
                }
                return messages;
            });
        });
    }

    @Override
    public void markSent(int id, ZonedDateTime sentAt) {
        DbUtil.withPreparedStmtConsumer(ds, """
                UPDATE outbound_messages
                SET status = ?, attempt_count = attempt_count + 1, last_error = NULL,
                    next_attempt_at = NULL, next_attempt_at_epoch_s = NULL, sent_at = ?, sent_at_epoch_s = ?
                WHERE id = ?
                """, stmt -> {
            stmt.setString(1, OutboundMessageStatus.SENT.name());
            stmt.setString(2, ISO_DATE_TIME.format(sentAt));
            stmt.setLong(3, sentAt.toEpochSecond());
            stmt.setInt(4, id);
            stmt.executeUpdate();
        });
    }

    @Override
    public void scheduleRetry(int id, String lastError, ZonedDateTime nextAttemptAt) {
        DbUtil.withPreparedStmtConsumer(ds, """
                UPDATE outbound_messages
                SET status = ?, attempt_count = attempt_count + 1, last_error = ?, next_attempt_at = ?, next_attempt_at_epoch_s = ?, sent_at = NULL, sent_at_epoch_s = NULL
                WHERE id = ?
                """, stmt -> {
            stmt.setString(1, OutboundMessageStatus.QUEUED.name());
            stmt.setString(2, lastError);
            stmt.setString(3, ISO_DATE_TIME.format(nextAttemptAt));
            stmt.setLong(4, nextAttemptAt.toEpochSecond());
            stmt.setInt(5, id);
            stmt.executeUpdate();
        });
    }

    @Override
    public void markFailed(int id, String lastError) {
        DbUtil.withPreparedStmtConsumer(ds, """
                UPDATE outbound_messages
                SET status = ?, attempt_count = attempt_count + 1, last_error = ?, next_attempt_at = NULL, next_attempt_at_epoch_s = NULL
                WHERE id = ?
                """, stmt -> {
            stmt.setString(1, OutboundMessageStatus.FAILED.name());
            stmt.setString(2, lastError);
            stmt.setInt(3, id);
            stmt.executeUpdate();
        });
    }

    private static OutboundMessage toOutboundMessage(ResultSet rs) throws SQLException {
        Integer sourceMessageId = (Integer) rs.getObject(3);
        String lastError = rs.getString(13);
        String nextAttemptAt = rs.getString(15);
        String sentAt = rs.getString(17);
        return new OutboundMessage(
                rs.getInt(1),
                rs.getInt(2),
                Optional.ofNullable(sourceMessageId),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10),
                OutboundMessageStatus.valueOf(rs.getString(11)),
                rs.getInt(12),
                Optional.ofNullable(lastError),
                ZonedDateTime.parse(rs.getString(14), ISO_DATE_TIME),
                Optional.ofNullable(nextAttemptAt).map(value -> ZonedDateTime.parse(value, ISO_DATE_TIME)),
                Optional.ofNullable(sentAt).map(value -> ZonedDateTime.parse(value, ISO_DATE_TIME)),
                rs.getLong(19));
    }
}
