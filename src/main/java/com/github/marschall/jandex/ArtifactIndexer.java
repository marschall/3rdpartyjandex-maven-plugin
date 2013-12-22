package com.github.marschall.jandex;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.StandardOpenOption.READ;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PACKAGE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;

@Mojo(name = "index",
  threadSafe = true,
  defaultPhase = PACKAGE)
@Execute(goal = "index",
  phase = PACKAGE)
public class ArtifactIndexer extends AbstractMojo {
  

  /**
   * The folder that contains the JARs which should be indexed.
   */
  @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}")
  private File artifact;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    Path artifactPath = this.artifact.toPath();
    if (!Files.exists(artifactPath)) {
        throw new MojoExecutionException("artifact " + this.artifact + " does not exists, run package first");
    }
  }
  
  private boolean containsSubDeplyoments(String extension) {
    return "ear".equals(extension)
        || "war".equals(extension)
        || "rar".equals(extension);
  }
  
  private boolean containsClasses(String extension) {
    return "jar".equals(extension)
        || "war".equals(extension);
  }
  
  private void index(Path jar) throws IOException, MojoExecutionException {
    Map<String, String> env = Collections.singletonMap("create", "false"); 
    // locate file system by using the syntax 
    // defined in java.net.JarURLConnection
    URI uri = URI.create("jar:" + jar.toUri());
    String fileName = jar.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex == -1) {
      throw new MojoExecutionException("cold not determine type of artifact: " + jar);
    }
    String extension = fileName.substring(dotIndex + 1);
    try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
      if (containsClasses(extension)) {
        Path root = zipfs.getPath("/");
        Path jandexIdx = root.resolve("META-INF/jandex.idx");
        if (!Files.exists(jandexIdx)) {
          Index index = buildIndex(zipfs);
        }
      }
      if (containsSubDeplyoments(extension)) {
        indexSubdeplyoments(zipfs);
      }
    }
  }
  
  private void indexSubdeplyoments(FileSystem zipfs) throws IOException {
    
  }
  
  private Index buildIndex(FileSystem zipfs) throws IOException {
    Path root = zipfs.getPath("/");
    Path jandexIdx = root.resolve("META-INF/jandex.idx");
    if (Files.exists(jandexIdx)) {
      return null;
    }

    final Indexer indexer = new Indexer();
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {


      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.isRegularFile()) {
          boolean isClass = file.getFileName().toString().endsWith(".class");
          if (isClass) {
            try (InputStream inputStream = Files.newInputStream(file, READ)) {
              // indexer does buffering
              indexer.index(inputStream);
            }
          }
        }
        return CONTINUE;
      }
    });
    return indexer.complete();
  }


}
