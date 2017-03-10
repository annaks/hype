/*-
 * -\-\-
 * hype-run
 * --
 * Copyright (C) 2016 - 2017 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

import static com.google.cloud.storage.Bucket.BlobWriteOption.doesNotExist;
import static com.google.cloud.storage.Storage.BlobListOption.prefix;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Throwables;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

/**
 * TODO: document.
 */
public class Hypelet extends Capsule {

  private static final String STAGING_PREFIX = "hype-run-";
  private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

  private static final ForkJoinPool FJP = new ForkJoinPool(32);

  private final List<Path> downloadedJars = new ArrayList<>();

  private Storage storage;
  private URI stagingPrefix;
  private Path stagingDir;
  private String returnFile;

  public Hypelet(Capsule pred) {
    super(pred);
  }

  @Override
  protected ProcessBuilder prelaunch(List<String> jvmArgs, List<String> args) {
    if (args.size() < 2) {
      throw new IllegalArgumentException("Usage: <gcs-staging-uri> <continuation-file>");
    }

    try {
      storage = StorageOptions.getDefaultInstance().getService();
      stagingPrefix = URI.create(args.get(0));
      stagingDir = Files.createTempDirectory(STAGING_PREFIX);
      returnFile = args.get(1).replaceFirst("\\.bin", "-return.bin");

      try {
        downloadFiles(stagingPrefix, stagingDir);
      } catch (IOException | ExecutionException | InterruptedException e) {
        throw Throwables.propagate(e);
      }

      final List<String> stubArgs = new ArrayList<>(args.size());
      stubArgs.add(stagingDir.toString());
      stubArgs.add(args.get(1));
      stubArgs.add(returnFile);
      return super.prelaunch(jvmArgs, stubArgs);
    } catch (Throwable e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  protected void cleanup() {
    if (stagingDir != null) {
      final Path returnFilePath = stagingDir.resolve(returnFile);
      if (Files.exists(returnFilePath)) {
        System.out.println("Upload serialized return value: " + returnFilePath);
        upload(returnFilePath.toFile(), stagingPrefix);
      }
    }

    super.cleanup();
  }

  @Override
  protected Object lookup0(Object x, String type,
                           Map.Entry<String, ?> attrContext,
                           Object context) {
    final Object o = super.lookup0(x, type, attrContext, context);
    if ("App-Class-Path".equals(attrContext.getKey())) {
      final List<Path> lookup = new ArrayList<>((List<Path>) o);
      lookup.addAll(downloadedJars);
      return lookup;
    }
    return o;
  }

  private void downloadFiles(URI stagingPrefix, Path temp)
      throws IOException, ExecutionException, InterruptedException {

    if (!"gs".equals(stagingPrefix.getScheme())) {
      throw new IllegalArgumentException("Staging prefix must be a gs:// uri");
    }

    System.out.println("Downloading staging files from " + stagingPrefix + " -> " + temp);

    final String bucket =  stagingPrefix.getAuthority();
    final String prefix = stagingPrefix.getPath().substring(1); // remove leading '/'
    final Iterator<Blob> blobIterator =
        storage.list(bucket, prefix(prefix)).iterateAll();

    final List<Blob> stagedFiles = new ArrayList<>();
    while (blobIterator.hasNext()) {
      stagedFiles.add(blobIterator.next());
    }

    FJP.submit(
        () -> stagedFiles.parallelStream()
            .forEach(blob -> downloadFile(blob, temp)))
        .get();

    System.out.println("Done");
  }

  private void downloadFile(Blob blob, Path temp) {
    final String localFileName = Paths.get(blob.getName()).getFileName().toString();
    final boolean addToClasspath = localFileName.endsWith(".jar");
    final Path localFilePath = temp.resolve(localFileName);

    if (addToClasspath) {
      downloadedJars.add(localFilePath);
    }

    System.out.println("... downloading blob " + blob.getName() +
                       (addToClasspath ? " ++classpath" : ""));

    try (OutputStream bos = new BufferedOutputStream(new FileOutputStream(localFilePath.toFile()))) {
      try (ReadableByteChannel reader = blob.reader()) {
        final WritableByteChannel writer = Channels.newChannel(bos);
        final ByteBuffer buffer = ByteBuffer.allocate(8192);

        int read;
        while ((read = reader.read(buffer)) > 0) {
          buffer.rewind();
          buffer.limit(read);

          while (read > 0) {
            read -= writer.write(buffer);
          }

          buffer.clear();
        }
      }
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private URI upload(File file, URI uploadPrefix) {
    if (!"gs".equals(uploadPrefix.getScheme())) {
      throw new IllegalArgumentException("Staging prefix must be a gs:// uri");
    }

    final String bucketName =  uploadPrefix.getAuthority();
    final String prefix = uploadPrefix.getPath().substring(1); // remove leading '/'
    final Path uploadPath = Paths.get(prefix, file.getName());

    try (FileInputStream inputStream = new FileInputStream(file)) {
      Bucket bucket = storage.get(bucketName);
      String blobName = uploadPath.toString();
      Blob blob = bucket.create(blobName, inputStream, APPLICATION_OCTET_STREAM, doesNotExist());

      return new URI("gs", blob.getBucket(), "/" + blob.getName(), null);
    } catch (URISyntaxException | IOException e) {
      throw Throwables.propagate(e);
    }
  }
}