package net.aggregat4.quicksand.webservice;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

public final class StaticAssetWebService implements HttpService {
  private final String publicPrefix;
  private final StaticAssetRegistry registry;

  public StaticAssetWebService(String publicPrefix, StaticAssetRegistry registry) {
    this.publicPrefix = publicPrefix;
    this.registry = registry;
  }

  @Override
  public void routing(HttpRules rules) {
    rules.get("/{fileName}", this::serveAsset);
  }

  private void serveAsset(ServerRequest request, ServerResponse response) {
    String relativePath = request.path().path();
    if (relativePath == null || relativePath.isBlank()) {
      response.status(Status.NOT_FOUND_404);
      response.send();
      return;
    }
    if (relativePath.startsWith("/")) {
      relativePath = relativePath.substring(1);
    }
    String publicPath = publicPrefix + "/" + relativePath;
    var asset = registry.find(publicPath);
    if (asset.isEmpty()) {
      response.status(Status.NOT_FOUND_404);
      response.send();
      return;
    }
    StaticAssetRegistry.StaticAsset staticAsset = asset.get();
    String requestedVersion = request.query().first("v").orElse("");
    if (!requestedVersion.isBlank() && !requestedVersion.equals(staticAsset.contentHash())) {
      response.status(Status.NOT_FOUND_404);
      response.send();
      return;
    }

    String ifNoneMatch = request.headers().first(HeaderNames.IF_NONE_MATCH).orElse("");
    if (ResponseUtils.ifNoneMatchMatches(ifNoneMatch, staticAsset.etag())) {
      response.status(Status.NOT_MODIFIED_304);
      response.headers().set(HeaderNames.ETAG, staticAsset.etag());
      ResponseUtils.setCacheControlImmutable(response);
      response.send();
      return;
    }

    response.headers().contentType(staticAsset.mediaType());
    response.headers().set(HeaderNames.ETAG, staticAsset.etag());
    ResponseUtils.setCacheControlImmutable(response);
    response.send(staticAsset.content());
  }
}
