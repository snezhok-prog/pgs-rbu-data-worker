FROM newharbor.evolenta.ru/sier5/base25
MAINTAINER Dmitriy Mayrinov <mayrinov@evolenta.ru>
ARG UNAME=appuser
ENV USER=$UNAME
ARG UID=10001
ARG GID=10001
RUN groupadd -g "${GID}" "${UNAME}" && useradd -m --no-log-init -s /bin/bash -u "${UID}" -g "${GID}" "${UNAME}"
ENV JAVA_OPTS="-Xms256M -Xmx1024M"
COPY /opt /opt/
RUN chown -R "${UNAME}" /opt/
RUN mkdir -p /log && mkdir -p /usr/app/log
RUN chmod -R 777 /log && chmod -R 777 /usr/app/log
RUN /opt/java/bin/keytool -importcert -trustcacerts -cacerts -storepass changeit -noprompt -file /opt/rootCA.PD15.GTP.crt -alias rootCA.PD15.GTP.crt && /opt/java/bin/keytool -importcert -trustcacerts -cacerts -storepass changeit -noprompt -file /opt/pd40.crt
EXPOSE 8080/tcp
USER "${UNAME}"
ENTRYPOINT ["sh", "-c", "/opt/java/bin/java -Dfile.encoding=UTF-8 $JAVA_OPTS -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -jar /opt/business-rbu-data-worker.jar"]