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
        if (groups.get(0).headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(groups.get(0).headers().get(0));
    }

    public Optional<EmailHeader> getLastEmailHeader() {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        EmailGroup lastGroup = groups.get(groups.size() - 1);
        if (lastGroup.headers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(lastGroup.headers().get(lastGroup.headers().size() - 1));
    }

}
