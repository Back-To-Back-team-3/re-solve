FROM eclipse-temurin:21-jre-noble
WORKDIR /app

RUN groupadd --system spring && \
    useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring

# Gradle이 만든 실행 JAR만 컨테이너에 넣는다.
COPY build/libs/ /tmp/libs/
RUN set -eu; \
    jar="$(find /tmp/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"; \
    test -n "$jar"; \
    mv "$jar" /app/app.jar; \
    chown spring:spring /app/app.jar; \
    rm -rf /tmp/libs

USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
