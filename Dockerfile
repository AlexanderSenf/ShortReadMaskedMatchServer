FROM openjdk:jre-alpine
VOLUME /tmp
ARG JAR_FILE

ENV _JAVA_OPTIONS "-Xms256m -Xmx4096m -Djava.awt.headless=true" -Djava.library.path=/usr/lib/jni

COPY ${JAR_FILE} /opt/bwaserver-1.0-SNAPSHOT.jar
RUN mkdir -p /usr/lib/jni
COPY jni/libbwajni.so /usr/lib/jni/libbwajni.so

WORKDIR /opt
ENTRYPOINT ["java", "-jar", "/opt/bwaserver-1.0-SNAPSHOT.jar"]
