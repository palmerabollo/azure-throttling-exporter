FROM maven:3.6.3-jdk-11 as builder

WORKDIR /opt/app

COPY pom.xml ./
RUN mvn verify

COPY src ./src
RUN mvn clean package

FROM gcr.io/distroless/java-debian10

COPY --from=builder /opt/app/target/azure-throttling-exporter-0.2.0-jar-with-dependencies.jar app.jar

ENTRYPOINT ["java", "-cp", "app.jar", "com.baikalplatform.azure.AzureMetricsExporter"]

