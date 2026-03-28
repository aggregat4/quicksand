package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Optional;

public record EmailGroupPage(List<EmailGroup> groups, Pagination pagination) {
    public int getNofMessages() {
        return groups.stream().mapToInt(EmailGroup::getNofMessages).sum();
    }

    public Optional<EmailHeader> getFirstEmailHeader() {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        EmailGroup firstGroup = groups.getFirst();
        if (firstGroup.headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(firstGroup.headers().getFirst());
    }

    public Optional<EmailHeader> getLastEmailHeader() {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        EmailGroup lastGroup = groups.getLast();
        if (lastGroup.headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(lastGroup.headers().getLast());
    }

}
