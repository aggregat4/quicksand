package net.aggregat4.quicksand.domain;

import java.util.List;

public record EmailPage(List<Email> emails, boolean hasLeft, boolean hasRight) {
}
