package net.aggregat4.quicksand.domain;

public enum ActorType {
    SENDER(1),
    TO(2),
    CC(3),
    BCC(4);

    private final int value;

    ActorType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
