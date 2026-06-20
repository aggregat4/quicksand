package net.aggregat4.quicksand.webservice;

import io.helidon.http.HttpMediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.aggregat4.quicksand.util.ContentHasher;

public final class StaticAssetRegistry {
  private static volatile StaticAssetRegistry instance;

  private final Map<String, StaticAsset> assetsByPublicPath;

  private StaticAssetRegistry(Map<String, StaticAsset> assetsByPublicPath) {
    this.assetsByPublicPath = Map.copyOf(assetsByPublicPath);
  }

  public static StaticAssetRegistry get() {
    StaticAssetRegistry loaded = instance;
    if (loaded == null) {
      synchronized (StaticAssetRegistry.class) {
        loaded = instance;
        if (loaded == null) {
          loaded = load();
          instance = loaded;
        }
      }
    }
    return loaded;
  }

  static void resetForTests() {
    instance = null;
  }

  public String url(String publicPath) {
    String normalizedPath = normalizePublicPath(publicPath);
    StaticAsset asset =
        Optional.ofNullable(assetsByPublicPath.get(normalizedPath))
            .orElseThrow(
                () -> new IllegalArgumentException("Unknown static asset path: " + publicPath));
    return normalizedPath + "?v=" + asset.contentHash();
  }

  Optional<StaticAsset> find(String publicPath) {
    return Optional.ofNullable(assetsByPublicPath.get(normalizePublicPath(publicPath)));
  }

  List<String> publicPathsWithPrefix(String prefix) {
    String normalizedPrefix = normalizePublicPath(prefix);
    return assetsByPublicPath.keySet().stream()
        .filter(path -> path.startsWith(normalizedPrefix))
        .sorted()
        .toList();
  }

  private static String normalizePublicPath(String publicPath) {
    if (!publicPath.startsWith("/")) {
      return "/" + publicPath;
    }
    return publicPath;
  }

  private static StaticAssetRegistry load() {
    Map<String, StaticAsset> assets = new LinkedHashMap<>();
    registerDirectory(assets, "static/css", "/css");
    registerDirectory(assets, "static/js", "/js");
    registerDirectory(assets, "static/images", "/images");
    return new StaticAssetRegistry(assets);
  }

  private static void registerDirectory(
      Map<String, StaticAsset> assets, String classpathDirectory, String publicPrefix) {
    List<String> relativePaths = ClasspathResourceScanner.listRelativePaths(classpathDirectory);
    for (String relativePath : relativePaths) {
      String classpathPath = classpathDirectory + "/" + relativePath;
      byte[] content = readClasspathBytes(classpathPath);
      String publicPath = publicPrefix + "/" + relativePath;
      String contentHash = ContentHasher.shortHash(content);
      assets.put(
          publicPath,
          new StaticAsset(
              content, contentHash, strongEtag(contentHash), mediaTypeFor(relativePath)));
    }
  }

  private static byte[] readClasspathBytes(String classpathPath) {
    ClassLoader classLoader = StaticAssetRegistry.class.getClassLoader();
    try (InputStream inputStream = classLoader.getResourceAsStream(classpathPath)) {
      if (inputStream == null) {
        throw new IllegalStateException("Missing classpath resource " + classpathPath);
      }
      return inputStream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read classpath resource " + classpathPath, e);
    }
  }

  private static HttpMediaType mediaTypeFor(String relativePath) {
    if (relativePath.endsWith(".css")) {
      return HttpMediaType.create("text/css; charset=UTF-8");
    }
    if (relativePath.endsWith(".js")) {
      return HttpMediaType.create("application/javascript; charset=UTF-8");
    }
    if (relativePath.endsWith(".svg")) {
      return HttpMediaType.create("image/svg+xml");
    }
    return HttpMediaType.create("application/octet-stream");
  }

  private static String strongEtag(String contentHash) {
    return "\"" + contentHash + "\"";
  }

  public record StaticAsset(
      byte[] content, String contentHash, String etag, HttpMediaType mediaType) {}
}
