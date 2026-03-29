package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DbDraftRepository implements DraftRepository {
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final DataSource ds;

    public DbDraftRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Draft create(Draft draft) {
        return DbUtil.withPreparedStmtFunction(ds, """
                INSERT INTO drafts (account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued, updated_at, updated_at_epoch_s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stmt -> {
            stmt.setInt(1, draft.accountId());
            stmt.setString(2, draft.type().name());
            if (draft.sourceMessageId().isPresent()) {
                stmt.setInt(3, draft.sourceMessageId().get());
            } else {
                stmt.setNull(3, java.sql.Types.INTEGER);
            }
            stmt.setString(4, draft.to());
            stmt.setString(5, draft.cc());
            stmt.setString(6, draft.bcc());
            stmt.setString(7, draft.subject());
            stmt.setString(8, draft.body());
            stmt.setInt(9, draft.queued() ? 1 : 0);
            stmt.setString(10, ISO_DATE_TIME.format(draft.updatedAt()));
            stmt.setLong(11, draft.updatedAtEpochSeconds());
            stmt.executeUpdate();
            try (ResultSet keyRs = stmt.getGeneratedKeys()) {
                if (!keyRs.next()) {
                    throw new IllegalStateException("Expected generated key when creating draft");
                }
                return new Draft(
                        keyRs.getInt(1),
                        draft.accountId(),
                        draft.type(),
                        draft.sourceMessageId(),
                        draft.to(),
                        draft.cc(),
                        draft.bcc(),
                        draft.subject(),
                        draft.body(),
                        draft.queued(),
                        draft.updatedAt(),
                        draft.updatedAtEpochSeconds());
            }
        });
    }

    @Override
    public Optional<Draft> findById(int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued, updated_at, updated_at_epoch_s
                FROM drafts
                WHERE id = ?
                """, stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> rs.next() ? Optional.of(toDraft(rs)) : Optional.empty());
        });
    }

    public Optional<Draft> findById(Connection con, int id) {
        return DbUtil.withPreparedStmtFunction(con, """
                SELECT id, account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued, updated_at, updated_at_epoch_s
                FROM drafts
                WHERE id = ?
                """, stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> rs.next() ? Optional.of(toDraft(rs)) : Optional.empty());
        });
    }

    @Override
    public List<Draft> findOpenByAccountId(int accountId) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued, updated_at, updated_at_epoch_s
                FROM drafts
                WHERE account_id = ? AND queued = 0
                ORDER BY updated_at_epoch_s DESC, id DESC
                """, stmt -> {
            stmt.setInt(1, accountId);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<Draft> drafts = new ArrayList<>();
                while (rs.next()) {
                    drafts.add(toDraft(rs));
                }
                return drafts;
            });
        });
    }

    @Override
    public void update(Draft draft) {
        DbUtil.withPreparedStmtConsumer(ds, """
                UPDATE drafts
                SET to_recipients = ?, cc_recipients = ?, bcc_recipients = ?, subject = ?, body = ?, queued = ?, updated_at = ?, updated_at_epoch_s = ?
                WHERE id = ?
                """, stmt -> {
            stmt.setString(1, draft.to());
            stmt.setString(2, draft.cc());
            stmt.setString(3, draft.bcc());
            stmt.setString(4, draft.subject());
            stmt.setString(5, draft.body());
            stmt.setInt(6, draft.queued() ? 1 : 0);
            stmt.setString(7, ISO_DATE_TIME.format(draft.updatedAt()));
            stmt.setLong(8, draft.updatedAtEpochSeconds());
            stmt.setInt(9, draft.id());
            stmt.executeUpdate();
        });
    }

    @Override
    public void delete(int id) {
        DbUtil.withPreparedStmtConsumer(ds, "DELETE FROM drafts WHERE id = ?", stmt -> {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        });
    }

    public void delete(Connection con, int id) {
        DbUtil.withPreparedStmtConsumer(con, "DELETE FROM drafts WHERE id = ?", stmt -> {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        });
    }

    private static Draft toDraft(ResultSet rs) throws SQLException {
        Integer sourceMessageId = (Integer) rs.getObject(4);
        return new Draft(
                rs.getInt(1),
                rs.getInt(2),
                DraftType.valueOf(rs.getString(3)),
                Optional.ofNullable(sourceMessageId),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getInt(10) == 1,
                ZonedDateTime.parse(rs.getString(11), ISO_DATE_TIME),
                rs.getLong(12));
    }
}
