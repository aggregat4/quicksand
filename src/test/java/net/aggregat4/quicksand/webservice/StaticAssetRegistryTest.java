package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticAssetRegistryTest {

  @Test
  void loadsMainJsFromClasspath() {
    StaticAssetRegistry registry = StaticAssetRegistry.get();
    assertTrue(registry.find("/js/main.js").isPresent());
    assertTrue(registry.url("/js/main.js").contains("main.js?v="));
  }
}
