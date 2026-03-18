
# 1st stage, build the app
FROM maven:3.9.11-eclipse-temurin-25 as build

WORKDIR /helidon

# Create a first layer to cache the "Maven World" in the local repository.
# Incremental docker builds will always resume after that, unless you update
# the pom
ADD pom.xml .
RUN mvn package -Dmaven.test.skip -Declipselink.weave.skip

# Do the Maven build!
# Incremental docker builds will resume here when you change sources
ADD src src
RUN mvn package -DskipTests

RUN echo "done!"

# 2nd stage, build the runtime image
FROM eclipse-temurin:25
WORKDIR /helidon

# Copy the binary built in the 1st stage
COPY --from=build /helidon/target/quicksand.jar ./
COPY --from=build /helidon/target/libs ./libs

CMD ["java", "-jar", "quicksand.jar"]

EXPOSE 8080
