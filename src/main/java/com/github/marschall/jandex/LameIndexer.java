package com.github.marschall.jandex;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PACKAGE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;

@Mojo(name = "index",
threadSafe = true,
defaultPhase = PACKAGE)
@Execute(goal = "index",
phase = PACKAGE)
public class LameIndexer extends AbstractMojo {

  /**
   * The folder that contains the JARs which should be indexed.
   */
  @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}")
  private File artifact;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (!this.artifact.exists()) {
      throw new MojoExecutionException("artifact " + this.artifact + " does not exists, run package first");
    }
    try {
      index(this.artifact);
    } catch (IOException e) {
      throw new MojoExecutionException("could not create indices", e);
    }
  }

  public static void main(String[] args) throws MojoExecutionException, IOException {
    for (String arg : args) {
      LameIndexer indexer = new LameIndexer();
      indexer.artifact = new File(arg);
      indexer.index(indexer.artifact);
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
  
  private void index(File jar) throws MojoExecutionException, IOException {
    index(new JarFile(jar));
  }

  private boolean index(JarFile jar) throws IOException, MojoExecutionException {
    Map<String, String> env = Collections.singletonMap("create", "false"); 
    // locate file system by using the syntax 
    // defined in java.net.JarURLConnection
    String fileName = jar.getName();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex == -1) {
      throw new MojoExecutionException("cold not determine type of artifact: " + jar);
    }
    String extension = fileName.substring(dotIndex + 1);
    boolean changed = false;
    if (containsClasses(extension)) {
      if (!containsIndex(jar)) {
        Index index = buildIndex(jar);
        writeIndex(jar, index);
        changed = true;
      }
    }
    if (containsSubDeplyoments(extension)) {
      if (indexSubdeplyoments(extension, jar)) {
        changed = true;
      }
    }
    return changed;
  }

  private void writeIndex(Path jar, Index index) throws IOException {
    Path indexFile = jar.resolveSibling(jar.getFileName().toString() + ".index");
    try (OutputStream outputStream = Files.newOutputStream(indexFile, WRITE, CREATE)) {
      IndexWriter indexWriter = new IndexWriter(outputStream);
      // IndexWriter does buffering
      indexWriter.write(index);
    }
  }

  private boolean containsIndex(JarFile jar) {
    return jar.getEntry("META-INF/jandex.idx") != null;
  }

  private boolean indexSubdeplyoments(String extension, JarFile jar) throws IOException, MojoExecutionException {
    boolean changed = false;
    for (JarEntry subdeployment : findSubdeployments(extension, jar)) {
      boolean subDeploymentChanged = index(subdeployment);
      if (subDeploymentChanged) {
        // TODO update subdeployment
        changed = true;
      }
    }
    return changed;
  }

  private Collection<JarEntry> findSubdeployments(String extension, JarFile jar) throws IOException {
    List<JarEntry> jars = new ArrayList<>();
    switch (extension) {
      // TODO only check specific locations
      case "ear":
        for (JarEntry entry : asIterable(jar.entries())) {
          String entryName = entry.getName();
          if (entryName.endsWith(".jar") || entryName.endsWith(".war") || entryName.endsWith(".rar")) {
            jars.add(entry);
          }
        }
        return jars;
      case "war":
      case "rar":
        for (JarEntry entry : asIterable(jar.entries())) {
          String entryName = entry.getName();
          if (entryName.endsWith(".jar")) {
            jars.add(entry);
          }
        }
        return jars;
      default:
        throw new IllegalArgumentException("uknown deployment container: " + extension);
    }
  }

  private Index buildIndex(JarFile jar) throws IOException {
    final Indexer indexer = new Indexer();
    for (JarEntry entry : asIterable(jar.entries())) {
      if (entry.getName().endsWith(".jar")) {
        try (InputStream inputStream = jar.getInputStream(entry)) {
          indexer.index(inputStream);
        }
      }
    }
    return indexer.complete();
  }
  
  private static <T> Iterable<T> asIterable(Enumeration<T> enumeration) {
    return new IterableAdapter<>(new EnermerationAdapter<>(enumeration));
  }

  static final class IterableAdapter<T> implements Iterable<T> {

    private final Iterator<T> iterator;

    IterableAdapter(Iterator<T> iterator) {
      this.iterator = iterator;
    }

    @Override
    public Iterator<T> iterator() {
      return this.iterator;
    }

  }

  static final class EnermerationAdapter<E> implements Iterator<E> {

    private final Enumeration<E> enumeration;

    EnermerationAdapter(Enumeration<E> enumeration) {
      this.enumeration = enumeration;
    }

    @Override
    public boolean hasNext() {
      return this.enumeration.hasMoreElements();
    }

    @Override
    public E next() {
      return this.enumeration.nextElement();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("remove");
    }

  }

}
