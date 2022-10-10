package net.aggregat4.quicksand;

import java.util.Optional;

public record Actor(String emailAddress, Optional<String> name) {
}
