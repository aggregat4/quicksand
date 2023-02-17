package net.aggregat4.quicksand.domain;

import java.util.Optional;

public record Actor(ActorType type, String emailAddress, Optional<String> name) {

    @Override
    public String toString() {
        if (name.isPresent()) {
            return name.get() + " <" + emailAddress + ">";
        } else {
            return "<" + emailAddress + ">";
        }
    }
}
