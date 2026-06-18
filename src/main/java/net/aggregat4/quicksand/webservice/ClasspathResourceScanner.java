package net.aggregat4.quicksand.webservice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

final class ClasspathResourceScanner {
  private ClasspathResourceScanner() {}

  static List<String> listRelativePaths(String classpathDirectory) {
    ClassLoader classLoader = ClasspathResourceScanner.class.getClassLoader();
    URL rootUrl = classLoader.getResource(classpathDirectory);
    if (rootUrl == null) {
      return List.of();
    }
    try {
      return switch (rootUrl.getProtocol()) {
        case "file" -> listFileSystemPaths(Path.of(rootUrl.toURI()), Path.of(classpathDirectory));
        case "jar" -> listJarPaths(rootUrl, classpathDirectory);
        default -> List.of();
      };
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan classpath directory " + classpathDirectory, e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(
          "Failed to scan classpath directory " + classpathDirectory, e);
    }
  }

  private static List<String> listFileSystemPaths(Path rootPath, Path classpathRoot)
      throws IOException {
    if (!Files.isDirectory(rootPath)) {
      return List.of();
    }
    List<String> relativePaths = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(rootPath)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(path -> relativePaths.add(relativePath(path, rootPath)));
    }
    Collections.sort(relativePaths);
    return relativePaths;
  }

  private static List<String> listJarPaths(URL rootUrl, String classpathDirectory)
      throws IOException, URISyntaxException {
    String jarUri = rootUrl.toString();
    int separatorIndex = jarUri.indexOf('!');
    if (separatorIndex < 0) {
      return List.of();
    }
    URI fileSystemUri = URI.create(jarUri.substring(0, separatorIndex));
    Path jarRoot = Path.of(classpathDirectory);
    try (FileSystem fileSystem = FileSystems.newFileSystem(fileSystemUri, Collections.emptyMap())) {
      Path rootPath = fileSystem.getPath(jarRoot.toString());
      if (!Files.isDirectory(rootPath)) {
        return List.of();
      }
      List<String> relativePaths = new ArrayList<>();
      try (Stream<Path> paths = Files.walk(rootPath)) {
        paths
            .filter(Files::isRegularFile)
            .forEach(path -> relativePaths.add(relativePath(path, rootPath)));
      }
      Collections.sort(relativePaths);
      return relativePaths;
    }
  }

  private static String relativePath(Path filePath, Path rootPath) {
    return rootPath.relativize(filePath).toString().replace('\\', '/');
  }
}
