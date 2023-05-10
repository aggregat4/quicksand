package net.aggregat4.quicksand.domain;

public record PageParams(PageDirection pageDirection, SortOrder sortOrder) {

    public boolean isAscending() {
        return sortOrder == SortOrder.ASCENDING;
    }

    public String getOffsetComparator() {
        if (sortOrder == SortOrder.DESCENDING && pageDirection == PageDirection.RIGHT) {
            return "older";
        } else if (sortOrder == SortOrder.ASCENDING && pageDirection == PageDirection.LEFT) {
            return "older";
        } else if (sortOrder == SortOrder.ASCENDING && pageDirection == PageDirection.RIGHT) {
            return "newer";
        } else if (sortOrder == SortOrder.DESCENDING && pageDirection == PageDirection.LEFT) {
            return "newer";
        } else {
            throw new IllegalStateException();
        }
    }

    public String getSortString() {
        return sortOrder == SortOrder.ASCENDING ? "ASCENDING" : "DESCENDING";
    }
}
