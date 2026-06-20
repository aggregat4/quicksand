package net.aggregat4.quicksand.webservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImportMapRendererTest {

  @Test
  void includesJsModulesWithHashedUrls() {
    String json = ImportMapRenderer.json();
    assertTrue(json.startsWith("{\"imports\":{"));
    assertTrue(json.contains("\"quicksand/shell/app.js\":\"/js/shell/app.js?v="));
    assertTrue(json.contains("\"quicksand/lib/dom-ready.js\":\"/js/lib/dom-ready.js?v="));
  }
}
