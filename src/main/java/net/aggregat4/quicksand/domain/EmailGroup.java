package net.aggregat4.quicksand.domain;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public record EmailGroup(List<EmailHeader> headers, GroupedPeriod period) {

    public Optional<String> name() {
        return period.displayName();
    }

    public static List<EmailGroup> createNoGroupEmailgroup(List<EmailHeader> emailHeaders) {
        return List.of(new EmailGroup(emailHeaders, new GroupedPeriod() {
            @Override
            public Optional<String> displayName() {
                return Optional.empty();
            }

            @Override
            public ZonedDateTime startOfPeriod(Clock clock) {
                return null;
            }

            @Override
            public ZonedDateTime startOfNextPeriod(Clock clock) {
                return null;
            }
        }));
    }

    /**
     * Assumes that all headers are sorted according to the provided sort order.
     */
    public static List<EmailGroup> createEmailGroups(List<EmailHeader> emailHeaders, Clock clock, SortOrder sortOrder) {
        List<EmailGroup> groups = new ArrayList<>();
        GroupedPeriods[] groupedPeriods = orderedPeriods(sortOrder);
        int currentPeriodIndex = 0;
        GroupedPeriod currentGroupedPeriod = groupedPeriods[currentPeriodIndex];
        List<EmailHeader> currentGroupHeaders = new ArrayList<>();
        for (EmailHeader emailHeader: emailHeaders) {
            while (true) {
                if (currentGroupedPeriod.matches(emailHeader, clock)) {
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
        if (! currentGroupHeaders.isEmpty()) {
            groups.add(new EmailGroup(currentGroupHeaders, currentGroupedPeriod));
        }
        return groups;
    }

    private static GroupedPeriods[] orderedPeriods(SortOrder sortOrder) {
        GroupedPeriods[] groupedPeriods = GroupedPeriods.values();
        if (sortOrder == SortOrder.DESCENDING) {
            return groupedPeriods;
        }
        List<GroupedPeriods> reversed = new ArrayList<>(Arrays.asList(groupedPeriods));
        Collections.reverse(reversed);
        return reversed.toArray(new GroupedPeriods[0]);
    }

    public int getNofMessages() {
        return headers.size();
    }

}
