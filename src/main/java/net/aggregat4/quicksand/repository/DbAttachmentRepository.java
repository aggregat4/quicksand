package net.aggregat4.quicksand.repository;

import io.helidon.http.HttpMediaType;
import net.aggregat4.dblib.DbUtil;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.StoredAttachment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class DbAttachmentRepository implements AttachmentRepository {
    private final DataSource ds;

    public DbAttachmentRepository(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Attachment saveDraftAttachment(int draftId, String name, long sizeInBytes, HttpMediaType mediaType, String contentHash, byte[] content) {
        return DbUtil.withPreparedStmtFunction(ds, """
                INSERT INTO attachments (draft_id, message_id, name, size_bytes, media_type, content_hash, content)
                VALUES (?, NULL, ?, ?, ?, ?, ?)
                """, stmt -> {
            stmt.setInt(1, draftId);
            stmt.setString(2, name);
            stmt.setLong(3, sizeInBytes);
            stmt.setString(4, mediaType.text());
            stmt.setString(5, contentHash);
            stmt.setBytes(6, content);
            stmt.executeUpdate();
            try (ResultSet keyRs = stmt.getGeneratedKeys()) {
                if (!keyRs.next()) {
                    throw new IllegalStateException("Expected generated key when creating attachment");
                }
                return new Attachment(keyRs.getInt(1), name, sizeInBytes, mediaType);
            }
        });
    }

    @Override
    public List<Attachment> findByDraftId(int draftId) {
        return findByAssociation("draft_id", draftId);
    }

    @Override
    public List<Attachment> findByOutboundMessageId(int outboundMessageId) {
        return findByAssociation("outbound_message_id", outboundMessageId);
    }

    @Override
    public List<StoredAttachment> findStoredByOutboundMessageId(int outboundMessageId) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, draft_id, message_id, outbound_message_id, name, size_bytes, media_type, content_hash, content
                FROM attachments
                WHERE outbound_message_id = ?
                ORDER BY id
                """, stmt -> {
            stmt.setInt(1, outboundMessageId);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<StoredAttachment> attachments = new java.util.ArrayList<>();
                while (rs.next()) {
                    attachments.add(toStoredAttachment(rs));
                }
                return attachments;
            });
        });
    }

    @Override
    public Optional<StoredAttachment> findStoredById(int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, draft_id, message_id, outbound_message_id, name, size_bytes, media_type, content_hash, content
                FROM attachments
                WHERE id = ?
                """, stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> rs.next() ? Optional.of(toStoredAttachment(rs)) : Optional.empty());
        });
    }

    @Override
    public void moveDraftAttachmentsToOutboundMessage(Connection con, int draftId, int outboundMessageId) {
        DbUtil.withPreparedStmtConsumer(con, """
                UPDATE attachments
                SET draft_id = NULL, outbound_message_id = ?
                WHERE draft_id = ?
                """, stmt -> {
            stmt.setInt(1, outboundMessageId);
            stmt.setInt(2, draftId);
            stmt.executeUpdate();
        });
    }

    @Override
    public void deleteByDraftId(int draftId) {
        DbUtil.withPreparedStmtConsumer(ds, "DELETE FROM attachments WHERE draft_id = ?", stmt -> {
            stmt.setInt(1, draftId);
            stmt.executeUpdate();
        });
    }

    private List<Attachment> findByAssociation(String columnName, int id) {
        return DbUtil.withPreparedStmtFunction(ds, """
                SELECT id, name, size_bytes, media_type
                FROM attachments
                WHERE %s = ?
                ORDER BY id
                """.formatted(columnName), stmt -> {
            stmt.setInt(1, id);
            return DbUtil.withResultSetFunction(stmt, rs -> {
                List<Attachment> attachments = new java.util.ArrayList<>();
                while (rs.next()) {
                    attachments.add(new Attachment(
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getLong(3),
                            HttpMediaType.create(rs.getString(4))));
                }
                return attachments;
            });
        });
    }

    private static StoredAttachment toStoredAttachment(ResultSet rs) throws SQLException {
        Integer draftId = (Integer) rs.getObject(2);
        Integer messageId = (Integer) rs.getObject(3);
        Integer outboundMessageId = (Integer) rs.getObject(4);
        return new StoredAttachment(
                rs.getInt(1),
                Optional.ofNullable(draftId),
                Optional.ofNullable(messageId),
                Optional.ofNullable(outboundMessageId),
                rs.getString(5),
                rs.getLong(6),
                HttpMediaType.create(rs.getString(7)),
                rs.getString(8),
                rs.getBytes(9));
    }
}
