package net.aggregat4.quicksand.repository;

import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Draft;
import net.aggregat4.quicksand.domain.DraftType;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class DbDraftRepository implements DraftRepository {
    private final DataSource ds;

    public DbDraftRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Draft create(Draft draft) {
        return DbUtil.withPreparedStmtFunction(ds, """
                INSERT INTO drafts (account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        draft.queued());
            }
        });
    }

    @Override
    public Optional<Draft> findById(int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, account_id, type, source_message_id, to_recipients, cc_recipients, bcc_recipients, subject, body, queued
                FROM drafts
                WHERE id = ?
                """, stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> rs.next() ? Optional.of(toDraft(rs)) : Optional.empty());
        });
    }

    @Override
    public void update(Draft draft) {
        DbUtil.withPreparedStmtConsumer(ds, """
                UPDATE drafts
                SET to_recipients = ?, cc_recipients = ?, bcc_recipients = ?, subject = ?, body = ?, queued = ?
                WHERE id = ?
                """, stmt -> {
            stmt.setString(1, draft.to());
            stmt.setString(2, draft.cc());
            stmt.setString(3, draft.bcc());
            stmt.setString(4, draft.subject());
            stmt.setString(5, draft.body());
            stmt.setInt(6, draft.queued() ? 1 : 0);
            stmt.setInt(7, draft.id());
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
                rs.getInt(10) == 1);
    }
}
