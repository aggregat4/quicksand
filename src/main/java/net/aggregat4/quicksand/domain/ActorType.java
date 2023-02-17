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

    public static ActorType fromValue(int value) {
        for (ActorType type : ActorType.values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown value: " + value);
    }

    public int getValue() {
        return value;
    }
}
