# quicksand

## Java Baseline

Quicksand is a pure JVM application.

- Java 25 is the baseline
- GraalVM and `native-image` are no longer part of the project
- the default build artifact is the runnable JAR at `target/quicksand.jar`

## Build and Run

With Java 25 and Maven 3.9+:

```bash
mvn package
java -jar target/quicksand.jar
```

## Docker Image

Build the standard JVM image:

```text
docker build -t quicksand .
```

Run it:

```text
docker run --rm -p 8080:8080 quicksand:latest
```

## Starting a Test IMAP and SMTP Server

We use [GreenMail](https://greenmail-mail-test.github.io/greenmail/#deploy_docker_standalone) as a convenient Docker-based test server.

Pull a recent standalone image:

```text
docker pull greenmail/standalone:2.1.8
```

Then start it with forwarded ports and a test account:

```text
docker run -t -i -p 3025:3025 -p 3110:3110 -p 3143:3143 \
  -p 3465:3465 -p 3993:3993 -p 3995:3995 -p 8081:8080 \
  -e JAVA_OPTS=-Dgreenmail.users=test1:pwd1 \
  greenmail/standalone:2.1.8
```
