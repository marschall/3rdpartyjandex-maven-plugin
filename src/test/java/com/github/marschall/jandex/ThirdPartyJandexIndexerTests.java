package com.github.marschall.jandex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenExecution;
import io.takari.maven.testing.executor.MavenExecutionResult;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions("3.8.4")
public class ThirdPartyJandexIndexerTests {

  @Rule
  public final TestResources resources = new TestResources();

  private final MavenRuntime mavenRuntime;

  public ThirdPartyJandexIndexerTests(MavenRuntimeBuilder builder) throws Exception {
    this.mavenRuntime = builder
            .withCliOptions("--batch-mode")
            .build();
  }

  @Test
  public void wicket() throws Exception {
    File basedir = this.resources.getBasedir("wicket");
    MavenExecution execution = this.mavenRuntime.forProject(basedir);

    MavenExecutionResult result = execution.execute("clean", "package");
    result.assertErrorFreeLog();

    File target = new File(basedir, "target");
    File artifactFile = new File(target, "project-to-test-1.0-SNAPSHOT-indexed.war");
    assertTrue(artifactFile.exists());

    try (FileSystem zipFileSystem = ThirdPartyJandexIndexer.newZipFileSystem(artifactFile.toPath(), false);
         Stream<Path> pathStream = Files.walk(zipFileSystem.getPath("/"), 3)) {
      List<Path> jars = pathStream
          .filter(path -> Files.isRegularFile(path))
          .filter(path -> path.getFileName().toString().endsWith(".jar"))
          .collect(Collectors.toList());
      assertEquals(10, jars.size());
      for (Path jar : jars) {
        Path index = jar.resolveSibling(jar.getFileName() + ".index");
        assertTrue(Files.exists(index));
      }
    }
  }

}
