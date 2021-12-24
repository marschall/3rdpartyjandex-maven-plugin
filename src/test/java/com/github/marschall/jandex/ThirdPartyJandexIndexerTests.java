package com.github.marschall.jandex;

import java.io.File;

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
  }

}
