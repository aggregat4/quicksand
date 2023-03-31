package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Objects;

public record Email(
        EmailHeader header,
        boolean plainText,
        String body,
        List<Attachment> attachments) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Email email)) return false;
        return Objects.equals(header, email.header);
    }

    @Override
    public int hashCode() {
        return Objects.hash(header);
    }
}
