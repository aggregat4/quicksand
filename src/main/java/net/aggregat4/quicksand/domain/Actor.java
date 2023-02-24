package net.aggregat4.quicksand.domain;

import java.util.List;
import java.util.Optional;

public record Actor(ActorType type, String emailAddress, Optional<String> name) {

    public static Actor findActor(List<Actor> actors, ActorType type) {
        return actors.stream().filter(actor -> actor.type() == type).findFirst().orElseThrow();
    }

    @Override
    public String toString() {
        if (name.isPresent()) {
            return name.get() + " <" + emailAddress + ">";
        } else {
            return "<" + emailAddress + ">";
        }
    }
}
