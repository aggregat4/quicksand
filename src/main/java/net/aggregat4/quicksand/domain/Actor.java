package net.aggregat4.quicksand.domain;

import java.util.Optional;

public record Actor(String emailAddress, Optional<String> name) {
}
