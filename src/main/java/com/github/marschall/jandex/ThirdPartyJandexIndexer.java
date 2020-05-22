package com.github.marschall.jandex;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;

/**
 * Creates a Jandex index for 3rd party JARs.
 *
 * <p>For every JAR in a folder that doesn't already contain a
 * <code>META-INF/jandex.idx</code> create a new index file in that
 * folder with the name of the jar and <code>".index"</code> attached.</p>
 *
 * <p>This is mostly interesting for WAR and EAR files that contain 3rd party
 * JARs that aren't already indexed with Jandex.</p>
 *
 * @see <a href="https://github.com/wildfly/jandex-maven-plugin">wildfly/jandex-maven-plugin</a>
 */
@Mojo(
  name = "repackage",
  defaultPhase = LifecyclePhase.PACKAGE,
  requiresProject = true,
  threadSafe = true)
public class ThirdPartyJandexIndexer extends AbstractMojo {
  // 4th attempt

  private static final Set<String> SUPPORTED_PACKAGINGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ear", "war", "rar")));

  /**
   * The Maven project.
   */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  /**
   * Directory containing the generated archive.
   */
  @Parameter(defaultValue = "${project.build.directory}", required = true)
  private File outputDirectory;

  /**
   * Name of the generated archive.
   */
  @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
  private String finalName;

  /**
   * Skip the execution.
   */
  @Parameter(property = "thirdpartyjandexindexer.repackage.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (this.skip) {
      this.getLog().info("skipping plugin execution");
    }
    String packaging = this.project.getPackaging();
    if (!SUPPORTED_PACKAGINGS.contains(packaging)) {
      this.getLog().info("skipping plugin execution, unsupported packaging: " + packaging);
      return;
    }

    Artifact sourceArtifact = this.getSourceArtifact();

    File repackaged;
    try {
      repackaged = this.repackage(sourceArtifact.getFile());
    } catch (IOException e) {
      throw new MojoExecutionException("could not repackage file: " + sourceArtifact.getFile(), e);
    }
    sourceArtifact.setFile(repackaged);
  }

  private Artifact getSourceArtifact() {
    return this.project.getArtifact();
  }

  private File repackage(File file) throws IOException {
    File repackaged = new File(this.outputDirectory, this.finalName + "-indexed." + this.project.getPackaging());
    Files.copy(file.toPath(), repackaged.toPath());
    Path tempDirectory = Files.createTempDirectory("3rdpartyjandex-maven-plugin");
    try {
      unzipJars(file.toPath(), tempDirectory);
      List<Path> indices = this.indexJars(tempDirectory);
      this.addIndices(repackaged.toPath(), tempDirectory, indices);
      return repackaged;
    } finally {
      deleteRecursively(tempDirectory);
    }
  }

  private void addIndices(Path repackaged, Path baseDirectory, List<Path> indices) throws IOException {
    try (FileSystem zipFileSystem = newZipFileSystem(repackaged, false)) {
      Path zipRoot = zipFileSystem.getPath("/");
      for (Path index : indices) {
        Path target = zipRoot.resolve(baseDirectory.relativize(index).toString());
        Files.copy(index, target);
      }
    }
  }

  private List<Path> indexJars(Path tempDirectory) throws IOException {
    List<Path> indices = new ArrayList<>();

    Files.walkFileTree(tempDirectory, new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path fileName = file.getFileName();
        boolean isJar = fileName.endsWith(".jar");
        if (isJar) {
          try (FileSystem zipFileSystem = newZipFileSystem(file, false)) {
            if (!hasIndex(zipFileSystem)) {
              Indexer indexer = ThirdPartyJandexIndexer.indexJar(zipFileSystem);
              Path index = file.getParent().resolve(fileName.toString() + ".index");
              ThirdPartyJandexIndexer.writeIndex(indexer, index);
              indices.add(index);
            }
          }
        }
        return CONTINUE;
      }

    });
    return indices;
  }

  private static Indexer indexJar(FileSystem zipFileSystem) throws IOException {
    Indexer indexer = new Indexer();

    Files.walkFileTree(zipFileSystem.getPath("/"), new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path fileName = file.getFileName();
        boolean isClass = fileName.endsWith(".class");
        if (isClass) {
          try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(file))) {
            indexer.index(inputStream);
          }
        }
        return CONTINUE;
      }

    });

    return indexer;
  }

  private static void writeIndex(Indexer indexer, Path target) throws IOException {
    try (BufferedOutputStream indexOut = new BufferedOutputStream(Files.newOutputStream(target))) {
      IndexWriter writer = new IndexWriter( indexOut );
      Index index = indexer.complete();
      writer.write( index );
    }
  }

  private static boolean hasIndex(FileSystem zipFileSystem) {
    return Files.exists(zipFileSystem.getPath("META-INF", "jandex.idx"));
  }

  static void zip(Path directory, Path targetZipFile) throws IOException {
    try (FileSystem zipFileSystem = newZipFileSystem(targetZipFile, true)) {

      Path zipRoot = zipFileSystem.getPath("/");
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          Path relative = directory.relativize(dir);
          Path targetDir = zipRoot.resolve(relative.toString());
          if (!Files.exists(targetDir)) {
            Files.createDirectory(targetDir);
          }

          return CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Path relative = directory.relativize(file);
          Path targetFile = zipRoot.resolve(relative.toString());
          Files.copy(file, targetFile, REPLACE_EXISTING, COPY_ATTRIBUTES);

          return CONTINUE;
        }

      });
    }
  }

  static void unzipJars(Path zipFile, Path targetDirectory) throws IOException {
    try (FileSystem zipFileSystem = newZipFileSystem(zipFile, false)) {

      Path zipRoot = zipFileSystem.getPath("/");
      Files.walkFileTree(zipRoot, new SimpleFileVisitor<Path>() {

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          Path relative = zipRoot.relativize(dir);
          Path targetPath = targetDirectory.resolve(relative.toString());
          if (!Files.exists(targetPath)) {
            Files.createDirectory(targetPath);
          }

          return CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          boolean isJar = file.getFileName().endsWith(".jar");
          if (isJar) {
            Path relative = zipRoot.relativize(file);
            Path targetPath = targetDirectory.resolve(relative.toString());
            Files.copy(file, targetPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
          }
          return CONTINUE;
        }

      });
    }
  }

  static void unzip(Path zipFile, Path targetDirectory) throws IOException {
    try (FileSystem zipFileSystem = newZipFileSystem(zipFile, false)) {

      Path zipRoot = zipFileSystem.getPath("/");
      Files.walkFileTree(zipRoot, new SimpleFileVisitor<Path>() {

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          Path relative = zipRoot.relativize(dir);
          Path targetPath = targetDirectory.resolve(relative.toString());
          if (!Files.exists(targetPath)) {
            Files.createDirectory(targetPath);
          }

          return CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Path relative = zipRoot.relativize(file);
          Path targetPath = targetDirectory.resolve(relative.toString());
          Files.copy(file, targetPath, REPLACE_EXISTING, COPY_ATTRIBUTES);

          return CONTINUE;
        }

      });
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir);
        return CONTINUE;
      }

    });
  }

  static FileSystem newZipFileSystem(Path path, boolean create) throws IOException {
    Map<String, String> env = Collections.singletonMap("create", Boolean.toString(create));
    URI fileUri = path.toUri();
    URI zipUri;
    try {
      zipUri = new URI("jar:" + fileUri.getScheme(), fileUri.getPath(), null);
    } catch (URISyntaxException e) {
      throw new IOException("invalid uri syntax:" + fileUri, e);
    }
    return FileSystems.newFileSystem(zipUri, env);
  }

}
