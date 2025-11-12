FROM eclipse-temurin:17-jdk
RUN mkdir -p /opt/app/data
COPY build/schema-extractor-exec.jar /opt/app/schema-extractor.jar
WORKDIR /opt/app/data
EXPOSE 8080
ENTRYPOINT ["java","-jar","/opt/app/schema-extractor.jar"]