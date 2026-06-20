package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticAssetRegistryTest {

  @Test
  void loadsShellAppFromClasspath() {
    StaticAssetRegistry registry = StaticAssetRegistry.get();
    assertTrue(registry.find("/js/shell/app.js").isPresent());
    assertTrue(registry.url("/js/shell/app.js").contains("app.js?v="));
  }
}
