package com.github.marschall.jandex;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

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
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
 * JARs in the <code>lib/</code> folder that aren't indexed with Jandex.</p>
 * 
 * @see https://github.com/jdcasey/jandex-maven-plugin
 * @see http://maven.apache.org/plugin-tools/maven-plugin-plugin/examples/using-annotations.html
 */
@Mojo(name = "index", threadSafe = true)
@Execute(goal = "index", phase = LifecyclePhase.PACKAGE)
public class ThirdPartyIndexer extends AbstractMojo {
	
	
	/**
	 * The folder that contains the JARs which should be indexed.
	 */
	@Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}/lib")
	private File archiveFolder;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Path archtiveFolderPath = archiveFolder.toPath();
		if (!Files.exists(archtiveFolderPath)) {
			throw new MojoFailureException("folder " + archtiveFolderPath + " does not exist");
		}
		try {
			indexJarsInFolder(archtiveFolderPath);
		} catch (IOException e) {
			throw new MojoExecutionException("could not create indices", e);
		}
	}
	
	private void indexJarsInFolder(Path folder) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.jar")) {
			for (Path jar : stream) {
				createIndex(jar);
			}
		}
	}
	
	private void createIndex(Path jar) throws IOException {
		Index index = index(jar);
		if (index == null) {
			return;
		}
		
		Path indexFile = jar.resolveSibling(jar.getFileName().toString() + ".index");
		try (OutputStream outputStream = Files.newOutputStream(indexFile, WRITE, CREATE)) {
			IndexWriter indexWriter = new IndexWriter(outputStream);
			// IndexWriter does buffering
			indexWriter.write(index);
		}
	}
	
	private Index index(Path jar) throws IOException {
		Map<String, String> env = new HashMap<>(1); 
        env.put("create", "false");
        // locate file system by using the syntax 
        // defined in java.net.JarURLConnection
        URI uri = URI.create("jar:" + jar.toUri());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
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
					return FileVisitResult.CONTINUE;
				}
			});
        	return indexer.complete();
        }
	}

}
