# quicksand

TODO

## Build and run

With JDK17+

```bash
mvn package
java -jar target/quicksand.jar
```

## Building a Native Image

Make sure you have GraalVM locally installed:

```text
$GRAALVM_HOME/bin/native-image --version
```

Build the native image using the native image profile:

```text
mvn package -Pnative-image
```

This uses the helidon-maven-plugin to perform the native compilation using your installed copy of GraalVM. It might take a while to complete.
Once it completes start the application using the native executable (no JVM!):

```text
./target/quicksand
```

## Building the Docker Image

```text
docker build -t quicksand .
```

## Running the Docker Image

```text
docker run --rm -p 8080:8080 quicksand:latest
```

Exercise the application as described above.

## Building a Custom Runtime Image

Build the custom runtime image using the jlink image profile:

```text
mvn package -Pjlink-image
```

This uses the helidon-maven-plugin to perform the custom image generation.
After the build completes it will report some statistics about the build including the reduction in image size.

The target/quicksand-jri directory is a self contained custom image of your application. It contains your application,
its runtime dependencies and the JDK modules it depends on. You can start your application using the provide start script:

```text
./target/quicksand-jri/bin/start
```

Class Data Sharing (CDS) Archive
Also included in the custom image is a Class Data Sharing (CDS) archive that improves your application’s startup
performance and in-memory footprint. You can learn more about Class Data Sharing in the JDK documentation.

The CDS archive increases your image size to get these performance optimizations. It can be of significant size (tens of MB).
The size of the CDS archive is reported at the end of the build output.

If you’d rather have a smaller image size (with a slightly increased startup time) you can skip the creation of the CDS
archive by executing your build like this:

```text
mvn package -Pjlink-image -Djlink.image.addClassDataSharingArchive=false
```

For more information on available configuration options see the helidon-maven-plugin documentation.

## Starting a Test IMAP and SMTP Server

We're going to use [Greenmail](https://greenmail-mail-test.github.io/greenmail/#deploy_docker_standalone) as a convenent docker based test server so we can easily test our application.

First we get the new 2.0 image:
```text
docker pull greenmail/standalone:2.0.0-alpha-3
```

Then start it, do some port forwarding and set the user and password to some test values:

```text
docker run -t -i -p 3025:3025 -p 3110:3110 -p 3143:3143 \
  -p 3465:3465 -p 3993:3993 -p 3995:3995 -p 8081:8080 \
  -e JAVA_OPTS=-Dgreenmail.users=test1:pwd1 \
  greenmail/standalone:2.0.0-alpha-3
```

