package net.aggregat4.quicksand.jobs;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.MessageSet;

final class ImapBodyExtractor {
  record StoredBody(String body, boolean plainText, String excerpt) {}

  private record BodySelection(
      IMAPMessage message,
      String section,
      boolean plainText,
      String encoding,
      String contentType) {}

  private ImapBodyExtractor() {}

  static Map<IMAPMessage, StoredBody> extractBodies(IMAPFolder folder, List<IMAPMessage> messages)
      throws MessagingException {
    Map<IMAPMessage, BodySelection> selections = new LinkedHashMap<>();
    for (IMAPMessage message : messages) {
      Optional<BodySelection> selection = selectBody(message, message, null);
      selection.ifPresent(bodySelection -> selections.put(message, bodySelection));
    }

    Map<BodySelection, String> fetchedBodies = fetchSelectedBodySections(folder, selections);
    Map<IMAPMessage, StoredBody> storedBodies = new HashMap<>();
    for (IMAPMessage message : messages) {
      BodySelection selection = selections.get(message);
      String body = selection == null ? "" : fetchedBodies.getOrDefault(selection, "");
      storedBodies.put(
          message,
          new StoredBody(
              body,
              selection == null || selection.plainText(),
              createExcerpt(selection != null && !selection.plainText() ? stripHtml(body) : body)));
    }
    return storedBodies;
  }

  private static Optional<BodySelection> selectBody(IMAPMessage message, Part part, String section)
      throws MessagingException {
    if (isAttachment(part)) {
      return Optional.empty();
    }
    if (part.isMimeType("text/html")) {
      return Optional.of(
          new BodySelection(
              message, bodySection(section), false, getEncoding(part), part.getContentType()));
    }
    if (part.isMimeType("text/plain")) {
      return Optional.of(
          new BodySelection(
              message, bodySection(section), true, getEncoding(part), part.getContentType()));
    }
    try {
      if (part.isMimeType("multipart/alternative")) {
        return selectAlternativeBody(message, (Multipart) part.getContent(), section);
      }
      if (part.isMimeType("multipart/*")) {
        return selectMultipartBody(message, (Multipart) part.getContent(), section);
      }
      return Optional.empty();
    } catch (Exception e) {
      throw new MessagingException("Failed to inspect IMAP message body structure", e);
    }
  }

  private static Optional<BodySelection> selectAlternativeBody(
      IMAPMessage message, Multipart multipart, String parentSection) throws MessagingException {
    BodySelection plainText = null;
    BodySelection html = null;
    for (int i = 0; i < multipart.getCount(); i++) {
      BodyPart bodyPart = multipart.getBodyPart(i);
      Optional<BodySelection> selection =
          selectBody(message, bodyPart, childSection(parentSection, i));
      if (selection.isEmpty()) {
        continue;
      }
      if (selection.get().plainText()) {
        plainText = plainText == null ? selection.get() : plainText;
      } else {
        html = html == null ? selection.get() : html;
      }
    }
    return Optional.ofNullable(html != null ? html : plainText);
  }

  private static Optional<BodySelection> selectMultipartBody(
      IMAPMessage message, Multipart multipart, String parentSection) throws MessagingException {
    for (int i = 0; i < multipart.getCount(); i++) {
      BodyPart bodyPart = multipart.getBodyPart(i);
      Optional<BodySelection> selection =
          selectBody(message, bodyPart, childSection(parentSection, i));
      if (selection.isPresent()) {
        return selection;
      }
    }
    return Optional.empty();
  }

  private static Map<BodySelection, String> fetchSelectedBodySections(
      IMAPFolder folder, Map<IMAPMessage, BodySelection> selections) throws MessagingException {
    Map<String, List<BodySelection>> selectionsBySection = new LinkedHashMap<>();
    for (BodySelection selection : selections.values()) {
      selectionsBySection
          .computeIfAbsent(selection.section(), ignored -> new ArrayList<>())
          .add(selection);
    }

    Map<BodySelection, String> fetchedBodies = new HashMap<>();
    for (Map.Entry<String, List<BodySelection>> entry : selectionsBySection.entrySet()) {
      String section = entry.getKey();
      List<BodySelection> sectionSelections = entry.getValue();
      Map<Integer, BodySelection> selectionsByMessageNumber = new HashMap<>();
      int[] messageNumbers = new int[sectionSelections.size()];
      for (int i = 0; i < sectionSelections.size(); i++) {
        BodySelection selection = sectionSelections.get(i);
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
        BodySelection selection = selectionsByMessageNumber.get(fetchResponse.getNumber());
        BODY body = fetchResponse.getItem(BODY.class);
        if (selection != null && body != null) {
          fetchedBodies.put(selection, decodeBody(body, selection));
        }
      }
    }
    return fetchedBodies;
  }

  private static String decodeBody(BODY body, BodySelection selection) throws MessagingException {
    try {
      ByteArrayInputStream inputStream = body.getByteArrayInputStream();
      if (inputStream == null) {
        return "";
      }
      String encoding = selection.encoding();
      var decodedStream =
          encoding == null || encoding.isBlank()
              ? inputStream
              : MimeUtility.decode(inputStream, encoding);
      return new String(decodedStream.readAllBytes(), charset(selection.contentType()));
    } catch (Exception e) {
      throw new MessagingException("Failed to decode selected IMAP body section", e);
    }
  }

  private static Charset charset(String contentType) {
    try {
      String charset = new ContentType(contentType).getParameter("charset");
      if (charset != null && !charset.isBlank()) {
        return Charset.forName(charset);
      }
    } catch (Exception ignored) {
      // Fall through to UTF-8 as a pragmatic default for malformed or missing content types.
    }
    return StandardCharsets.UTF_8;
  }

  private static boolean isAttachment(Part part) throws MessagingException {
    return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
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

  private static String createExcerpt(String body) {
    String normalized = Objects.toString(body, "").replaceAll("\\s+", " ").trim();
    if (normalized.isEmpty()) {
      return "";
    }
    int excerptLength = Math.min(normalized.length(), 160);
    return normalized.substring(0, excerptLength);
  }

  private static String stripHtml(String html) {
    return Objects.toString(html, "")
        .replaceAll("(?is)<script.*?>.*?</script>", " ")
        .replaceAll("(?is)<style.*?>.*?</style>", " ")
        .replaceAll("(?is)<[^>]+>", " ")
        .replace("&nbsp;", " ");
  }
}
