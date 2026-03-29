package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.OutboundMessage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                    queued_at, queued_at_epoch_s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            stmt.setString(10, ISO_DATE_TIME.format(outboundMessage.queuedAt()));
            stmt.setLong(11, outboundMessage.queuedAtEpochSeconds());
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
                        outboundMessage.queuedAt(),
                        outboundMessage.queuedAtEpochSeconds());
            }
        });
    }

    @Override
    public Optional<OutboundMessage> findById(int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, source_message_id, from_name, from_address,
                       to_recipients, cc_recipients, bcc_recipients, subject, body,
                       queued_at, queued_at_epoch_s
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
                       queued_at, queued_at_epoch_s
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

    private static OutboundMessage toOutboundMessage(ResultSet rs) throws SQLException {
        Integer sourceMessageId = (Integer) rs.getObject(3);
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
                ZonedDateTime.parse(rs.getString(11), ISO_DATE_TIME),
                rs.getLong(12));
    }
}
