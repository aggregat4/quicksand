package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Objects;

/** Full message view. Equality is by header id only, not body content or attachments. */
public record Email(
    EmailHeader header,
    boolean plainText,
    String body,
    List<Attachment> attachments,
    List<InboundAttachment> inboundAttachments,
    String bodyContentHash) {

  public Email(EmailHeader header, boolean plainText, String body, List<Attachment> attachments) {
    this(header, plainText, body, attachments, List.of(), null);
  }

  public Email(
      EmailHeader header,
      boolean plainText,
      String body,
      List<Attachment> attachments,
      List<InboundAttachment> inboundAttachments) {
    this(header, plainText, body, attachments, inboundAttachments, null);
  }

  public Email {
    attachments = attachments == null ? List.of() : List.copyOf(attachments);
    inboundAttachments = inboundAttachments == null ? List.of() : List.copyOf(inboundAttachments);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Email email)) {
      return false;
    }
    return Objects.equals(header, email.header);
  }

  @Override
  public int hashCode() {
    return Objects.hash(header);
  }
}
