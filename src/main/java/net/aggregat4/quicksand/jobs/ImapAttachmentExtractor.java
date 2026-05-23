package net.aggregat4.quicksand.jobs;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.aggregat4.quicksand.domain.BinaryContent;
import net.aggregat4.quicksand.domain.InboundAttachment;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.MessageSet;

final class ImapAttachmentExtractor {
  record ExtractedAttachment(String name, String mediaType, BinaryContent content) {}

  private record AttachmentSelection(
      IMAPMessage message, String section, String encoding, String contentType, String fileName) {}

  private ImapAttachmentExtractor() {}

  static Map<IMAPMessage, List<ExtractedAttachment>> extractAttachments(
      IMAPFolder folder, List<IMAPMessage> messages) throws MessagingException {
    Map<IMAPMessage, List<AttachmentSelection>> selectionsByMessage = new LinkedHashMap<>();
    for (IMAPMessage message : messages) {
      List<AttachmentSelection> selections = new ArrayList<>();
      collectAttachmentSelections(message, message, null, selections);
      if (!selections.isEmpty()) {
        selectionsByMessage.put(message, selections);
      }
    }

    Map<AttachmentSelection, byte[]> fetchedContent =
        fetchSelectedAttachmentSections(folder, selectionsByMessage);
    Map<IMAPMessage, List<ExtractedAttachment>> extracted = new HashMap<>();
    for (Map.Entry<IMAPMessage, List<AttachmentSelection>> entry : selectionsByMessage.entrySet()) {
      List<ExtractedAttachment> attachments = new ArrayList<>();
      for (AttachmentSelection selection : entry.getValue()) {
        byte[] content = fetchedContent.getOrDefault(selection, new byte[0]);
        if (content.length == 0) {
          continue;
        }
        attachments.add(
            new ExtractedAttachment(
                normalizedFileName(selection.fileName(), selection.contentType()),
                normalizedMediaType(selection.contentType()),
                BinaryContent.of(content)));
      }
      if (!attachments.isEmpty()) {
        extracted.put(entry.getKey(), attachments);
      }
    }
    return extracted;
  }

  private static void collectAttachmentSelections(
      IMAPMessage message, Part part, String section, List<AttachmentSelection> selections)
      throws MessagingException {
    if (part.isMimeType("multipart/*")) {
      try {
        Multipart multipart = (Multipart) part.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
          collectAttachmentSelections(
              message, multipart.getBodyPart(i), childSection(section, i), selections);
        }
      } catch (Exception e) {
        throw new MessagingException("Failed to inspect IMAP message attachment structure", e);
      }
      return;
    }
    if (!shouldDownloadAsAttachment(part)) {
      return;
    }
    selections.add(
        new AttachmentSelection(
            message,
            bodySection(section),
            getEncoding(part),
            part.getContentType(),
            part.getFileName()));
  }

  private static boolean shouldDownloadAsAttachment(Part part) throws MessagingException {
    if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
      return true;
    }
    String fileName = part.getFileName();
    if (fileName != null && !fileName.isBlank()) {
      return true;
    }
    return !part.isMimeType("text/plain") && !part.isMimeType("text/html");
  }

  private static Map<AttachmentSelection, byte[]> fetchSelectedAttachmentSections(
      IMAPFolder folder, Map<IMAPMessage, List<AttachmentSelection>> selectionsByMessage)
      throws MessagingException {
    Map<String, List<AttachmentSelection>> selectionsBySection = new LinkedHashMap<>();
    for (List<AttachmentSelection> selections : selectionsByMessage.values()) {
      for (AttachmentSelection selection : selections) {
        selectionsBySection
            .computeIfAbsent(selection.section(), ignored -> new ArrayList<>())
            .add(selection);
      }
    }

    Map<AttachmentSelection, byte[]> fetchedContent = new HashMap<>();
    for (Map.Entry<String, List<AttachmentSelection>> entry : selectionsBySection.entrySet()) {
      String section = entry.getKey();
      List<AttachmentSelection> sectionSelections = entry.getValue();
      Map<Integer, AttachmentSelection> selectionsByMessageNumber = new HashMap<>();
      int[] messageNumbers = new int[sectionSelections.size()];
      for (int i = 0; i < sectionSelections.size(); i++) {
        AttachmentSelection selection = sectionSelections.get(i);
        int messageNumber = selection.message().getMessageNumber();
        messageNumbers[i] = messageNumber;
        selectionsByMessageNumber.put(messageNumber, selection);
      }
      MessageSet[] messageSets = MessageSet.createMessageSets(messageNumbers);
      Response[] responses =
          (Response[])
              folder.doCommand(
                  protocol -> {
                    Response[] fetched = protocol.fetch(messageSets, "BODY.PEEK[" + section + "]");
                    protocol.notifyResponseHandlers(fetched);
                    return fetched;
                  });
      for (Response response : responses) {
        if (!(response instanceof FetchResponse fetchResponse)) {
          continue;
        }
        AttachmentSelection selection = selectionsByMessageNumber.get(fetchResponse.getNumber());
        BODY body = fetchResponse.getItem(BODY.class);
        if (selection != null && body != null) {
          fetchedContent.put(selection, decodeAttachment(body, selection));
        }
      }
    }
    return fetchedContent;
  }

  private static byte[] decodeAttachment(BODY body, AttachmentSelection selection)
      throws MessagingException {
    try {
      ByteArrayInputStream inputStream = body.getByteArrayInputStream();
      if (inputStream == null) {
        return new byte[0];
      }
      String encoding = selection.encoding();
      var decodedStream =
          encoding == null || encoding.isBlank()
              ? inputStream
              : MimeUtility.decode(inputStream, encoding);
      return decodedStream.readAllBytes();
    } catch (Exception e) {
      throw new MessagingException("Failed to decode selected IMAP attachment section", e);
    }
  }

  private static String getEncoding(Part part) throws MessagingException {
    return part instanceof MimePart mimePart ? mimePart.getEncoding() : null;
  }

  private static String bodySection(String section) {
    return section == null ? "TEXT" : section;
  }

  private static String childSection(String parentSection, int zeroBasedIndex) {
    String child = Integer.toString(zeroBasedIndex + 1);
    return parentSection == null ? child : parentSection + "." + child;
  }

  private static String normalizedFileName(String fileName, String contentType) {
    if (fileName != null && !fileName.isBlank()) {
      return fileName;
    }
    return "attachment";
  }

  private static String normalizedMediaType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "application/octet-stream";
    }
    int semicolon = contentType.indexOf(';');
    return semicolon >= 0 ? contentType.substring(0, semicolon).trim() : contentType.trim();
  }

  static List<InboundAttachment> toInboundAttachments(List<ExtractedAttachment> extracted) {
    if (extracted == null || extracted.isEmpty()) {
      return List.of();
    }
    List<InboundAttachment> inbound = new ArrayList<>();
    for (ExtractedAttachment attachment : extracted) {
      inbound.add(
          new InboundAttachment(
              attachment.name(),
              attachment.mediaType(),
              AttachmentContentHasher.sha256Hex(attachment.content().bytes()),
              attachment.content()));
    }
    return inbound;
  }
}
