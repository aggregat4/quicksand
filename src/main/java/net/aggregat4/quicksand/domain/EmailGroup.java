package net.aggregat4.quicksand.domain;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record EmailGroup(List<EmailHeader> headers, GroupedPeriod period) {


    public static List<EmailGroup> createNoGroupEmailgroup(List<EmailHeader> emailHeaders) {
        return List.of(new EmailGroup(emailHeaders, new GroupedPeriod() {
            @Override
            public Optional<String> displayName() {
                return Optional.empty();
            }

            @Override
            public ZonedDateTime startOfPeriod() {
                return null;
            }

            @Override
            public ZonedDateTime startOfNextPeriod() {
                return null;
            }
        }));
    }

    /**
     * Assumes that all headers are sorted in descending chronological order.
     */
    public static List<EmailGroup> createEmailGroups(List<EmailHeader> emailHeaders) {
        List<EmailGroup> groups = new ArrayList<>();
        GroupedPeriods[] groupedPeriods = GroupedPeriods.values();
        int currentPeriodIndex = 0;
        GroupedPeriod currentGroupedPeriod = groupedPeriods[currentPeriodIndex];
        List<EmailHeader> currentGroupHeaders = new ArrayList<>();
        for (EmailHeader emailHeader: emailHeaders) {
            while (true) {
                if (currentGroupedPeriod.matches(emailHeader)) {
                    currentGroupHeaders.add(emailHeader);
                    break;
                } else {
                    if (currentPeriodIndex == groupedPeriods.length - 1) {
                        throw new IllegalStateException("We have reached the end of the available grouped periods but no groups matches this particular email, this should never happen. EmailHeader: %s".formatted(emailHeader));
                    }
                    if (!currentGroupHeaders.isEmpty()) {
                        groups.add(new EmailGroup(currentGroupHeaders, currentGroupedPeriod));
                    }
                    currentPeriodIndex++;
                    currentGroupedPeriod = groupedPeriods[currentPeriodIndex];
                    currentGroupHeaders = new ArrayList<>();
                }
            }
        }
        return groups;
    }

    public int getNofMessages() {
        return headers.size();
    }

}
