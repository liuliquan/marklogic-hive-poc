FROM docker.elastic.co/logstash/logstash:6.3.0

USER logstash

COPY logstash.yml /usr/share/logstash/config/logstash.yml
COPY pipeline.conf /usr/share/logstash/pipeline/logstash.conf

ENTRYPOINT ["/usr/local/bin/docker-entrypoint"]
