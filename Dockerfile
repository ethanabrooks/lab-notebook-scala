FROM ubuntu:18.04
COPY ./entrypoint.sh /
ENTRYPOINT ["/entrypoint.sh"]
