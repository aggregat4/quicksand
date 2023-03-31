package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Actor;
import net.aggregat4.quicksand.domain.ActorType;
import net.aggregat4.quicksand.domain.Email;
import net.aggregat4.quicksand.domain.EmailHeader;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class DbMessageRepository implements MessageRepository {
    private static final int IN_BATCH_SIZE = 100;
    private final DataSource ds;

    public DbMessageRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Optional<Email> findByMessageUid(long uid) {
        return DbUtil.withPreparedStmtFunction(ds, "SELECT id, imap_uid, subject, sent_date, received_date, body_excerpt, starred, read FROM messages WHERE imap_uid = ?", stmt -> {
            stmt.setLong(1, uid);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                if (rs.next()) {
                    int messageId = rs.getInt(1);
                    List<Actor> actors = getActors(messageId);
                    return Optional.of(convertRowToEmail(rs, actors));
                } else {
                    return Optional.empty();
                }
            });
        });
    }

    private static Email convertRowToEmail(ResultSet rs, List<Actor> actors) throws SQLException {
        return new Email(
                new EmailHeader(
                        rs.getInt(1),
                        rs.getLong(2),
                        actors,
                        rs.getString(3),
                        fromISOString(rs.getString(4)),
                        fromISOString(rs.getString(5)),
                        rs.getString(6),
                        rs.getInt(7) == 1,
                        false, /* TODO attachment handling */
                        rs.getInt(8) == 1),
                false,
                null,
                Collections.emptyList());
    }

    private List<Actor> getActors(int messageId) {
        return DbUtil.withPreparedStmtFunction(ds, "SELECT type, name, email_address FROM actors WHERE message_id = ?", stmt -> {
            stmt.setInt(1, messageId);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<Actor> actors = new ArrayList<>();
                while (rs.next()) {
                    actors.add(new Actor(ActorType.fromValue(rs.getInt(1)), rs.getString(2), Optional.ofNullable(rs.getString(3))));
                }
                return actors;
            });
        });
    }

    @Override
    public void updateFlags(int id, boolean messageStarred, boolean messageRead) {
        DbUtil.withPreparedStmtConsumer(ds, "UPDATE messages SET starred = ?, read = ? WHERE id = ?", stmt -> {
            stmt.setInt(1, messageStarred ? 1 : 0);
            stmt.setInt(2, messageRead ? 1 : 0);
            stmt.setInt(3, id);
        });
    }

    @Override
    public Set<Long> getAllMessageIds(int folderId) {
        return DbUtil.withPreparedStmtFunction(ds, "SELECT imap_uid FROM messages WHERE folder_id = ?", stmt -> {
            stmt.setInt(1, folderId);
            return DbUtil.withResultSetFunction(stmt, rs -> {
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
        if (! batch.isEmpty()) {
            removeBatchByUid(batch);
        }
    }

    @Override
    public int addMessage(int folderId, Email email) {
        return DbUtil.withPreparedStmtFunction(ds, """
                INSERT INTO messages (folder_id, imap_uid, subject, sent_date, received_date, body_excerpt, starred, read)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, stmt -> {
            stmt.setInt(1, folderId);
            stmt.setLong(2, email.header().imapUid());
            stmt.setString(3, email.header().subject());
            stmt.setString(4, toISOString(email.header().sentDateTime()));
            stmt.setString(5, toISOString(email.header().receivedDateTime()));
            stmt.setString(6, email.header().bodyExcerpt());
            stmt.setInt(7, email.header().starred() ? 1 : 0);
            stmt.setInt(8, email.header().read() ? 1 : 0);
            stmt.executeUpdate();
            try (ResultSet keyRs = stmt.getGeneratedKeys();) {
                if (!keyRs.next()) {
                    throw new IllegalStateException("We are expecting to get the generated keys when inserting a new message");
                }
                return keyRs.getInt(1);
            }
        });
    }

    @Override
    public void removeBatchByUid(List<Long> batch) {
        if (batch.size() > DbMessageRepository.IN_BATCH_SIZE) {
            throw new IllegalStateException("Only allowed to delete batches of maximally %s messages".formatted(DbMessageRepository.IN_BATCH_SIZE));
        }
        String inString = batch.stream().map(msgId -> "?").collect(Collectors.joining(", "));
        DbUtil.withPreparedStmtConsumer(ds, "DELETE FROM messages WHERE imap_message_id IN (%s)".formatted(inString), stmt -> {
            for (int i = 0; i < batch.size(); i++) {
                stmt.setLong(i + 1, batch.get(i));
            }
            stmt.executeUpdate();
        });
    }


    private static String toISOString(ZonedDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static ZonedDateTime fromISOString(String isoDate) {
        return ZonedDateTime.parse(isoDate, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
