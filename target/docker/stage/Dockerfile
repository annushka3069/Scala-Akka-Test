FROM java:latest
WORKDIR /opt/docker
ADD opt /opt
RUN ["chown", "-R", "daemon:daemon", "."]
USER daemon
EXPOSE 0 2551 2552
ENTRYPOINT ["bin/akka-cluster-sharding-scala"]
CMD []
