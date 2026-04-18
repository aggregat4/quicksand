package net.aggregat4.quicksand.repository;

import io.helidon.http.HttpMediaType;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.StoredAttachment;

public interface AttachmentRepository {
  Attachment saveDraftAttachment(
      int draftId,
      String name,
      long sizeInBytes,
      HttpMediaType mediaType,
      String contentHash,
      byte[] content);

  List<Attachment> findByDraftId(int draftId);

  List<Attachment> findByOutboundMessageId(int outboundMessageId);

  List<StoredAttachment> findStoredByOutboundMessageId(int outboundMessageId);

  Optional<StoredAttachment> findStoredById(int id);

  void moveDraftAttachmentsToOutboundMessage(Connection con, int draftId, int outboundMessageId);

  void deleteByDraftId(int draftId);
}
