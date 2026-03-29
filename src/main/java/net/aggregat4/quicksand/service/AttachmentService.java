package net.aggregat4.quicksand.service;

import io.helidon.http.HttpMediaType;
import net.aggregat4.quicksand.domain.Attachment;
import net.aggregat4.quicksand.domain.StoredAttachment;
import net.aggregat4.quicksand.repository.AttachmentRepository;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

public class AttachmentService {
    private final AttachmentRepository attachmentRepository;

    public AttachmentService(AttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
    }

    public Attachment storeDraftAttachment(int draftId, String name, HttpMediaType mediaType, InputStream data) throws IOException {
        byte[] content = data.readAllBytes();
        long sizeInBytes = content.length;
        return attachmentRepository.saveDraftAttachment(
                draftId,
                normalizedName(name),
                sizeInBytes,
                normalizedMediaType(mediaType),
                sha256Hex(content),
                content);
    }

    public List<Attachment> getDraftAttachments(int draftId) {
        return attachmentRepository.findByDraftId(draftId);
    }

    public List<Attachment> getOutboundAttachments(int outboundMessageId) {
        return attachmentRepository.findByOutboundMessageId(outboundMessageId);
    }

    public boolean hasDraftAttachments(int draftId) {
        return !attachmentRepository.findByDraftId(draftId).isEmpty();
    }

    public boolean hasOutboundAttachments(int outboundMessageId) {
        return !attachmentRepository.findByOutboundMessageId(outboundMessageId).isEmpty();
    }

    public Optional<StoredAttachment> getStoredAttachment(int attachmentId) {
        return attachmentRepository.findStoredById(attachmentId);
    }

    public void deleteDraftAttachments(int draftId) {
        attachmentRepository.deleteByDraftId(draftId);
    }

    private static String normalizedName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "attachment";
        }
        return originalName;
    }

    private static HttpMediaType normalizedMediaType(HttpMediaType mediaType) {
        if (mediaType == null) {
            return HttpMediaType.create("application/octet-stream");
        }
        return mediaType;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
