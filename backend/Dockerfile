# Dockerfile for URL Shortener Service backend (Java 25)
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

COPY target/url-shortener-service-1.0.0.jar app.jar

ENV SERVER_PORT=8080
ENV WORKER_NODE_ID=1
ENV DB_HOST=postgres
ENV DB_PORT=5432
ENV DB_NAME=url_shortener
ENV DB_USER=postgres
ENV DB_PASSWORD=postgres
ENV REDIS_HOST=redis
ENV REDIS_PORT=6379

EXPOSE ${SERVER_PORT}
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
