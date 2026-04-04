FROM sbtscala/scala-sbt:eclipse-temurin-25.0.1_8_1.12.8_3.8.2 AS build
LABEL authors="davicom"

WORKDIR /opt/build
COPY . .

RUN mkdir /opt/app
RUN sbt "set assembly / target := file(\"/opt/app\")" assembly

WORKDIR /opt/app
RUN echo -n "jdk.unsupported,java.management," > deps.info
RUN jdeps --ignore-missing-deps -q -recursive --print-module-deps greenPanditaBot.jar >> deps.info
RUN jlink --add-modules "$(cat deps.info)" --strip-debug --no-header-files --no-man-pages --compress=2 --output jre

FROM debian:stable-slim
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH "${JAVA_HOME}/bin:${PATH}"
COPY --from=build /opt/app/jre $JAVA_HOME
COPY --from=build /opt/app/greenPanditaBot.jar /opt/greenPanditaBot.jar

VOLUME /opt/classpath

WORKDIR /opt

ENV HTTP_PORT=80
ENV HTTP_HOST=localhost

EXPOSE 80

CMD ["java", "-cp", "/opt/classpath", "-Dgreenpandita.config=/opt/pandita.conf", "-jar", "greenPanditaBot.jar"]
