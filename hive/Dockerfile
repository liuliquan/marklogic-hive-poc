# Based on https://github.com/dariusgm/hive2
FROM openjdk:8-jre

# Need to update for ssh
RUN apt-get update && \
  apt-get install -y ssh

# Fetch Hadoop and Unpack & Fetch Hive and Unpack
WORKDIR /usr/local
RUN wget http://mirrors.ocf.berkeley.edu/apache/hadoop/common/hadoop-2.7.6/hadoop-2.7.6.tar.gz && \
  tar xf hadoop-2.7.6.tar.gz  && \
  wget http://mirrors.ocf.berkeley.edu/apache/hive/hive-2.3.3/apache-hive-2.3.3-bin.tar.gz && \
  tar xf apache-hive-2.3.3-bin.tar.gz

RUN wget http://mirrors.ocf.berkeley.edu/apache/tez/0.9.0/apache-tez-0.9.0-bin.tar.gz \
    && tar xf apache-tez-0.9.0-bin.tar.gz

RUN rm -f /usr/local/apache-tez-0.9.0-bin/lib/hadoop-mapreduce-client-*.jar && \
    cp /usr/local/hadoop-2.7.6/share/hadoop/mapreduce/hadoop-mapreduce-client-common-2.7.6.jar /usr/local/apache-tez-0.9.0-bin/lib/ && \
    cp /usr/local/hadoop-2.7.6/share/hadoop/mapreduce/hadoop-mapreduce-client-core-2.7.6.jar /usr/local/apache-tez-0.9.0-bin/lib/

WORKDIR /usr/local/hadoop-2.7.6/etc/hadoop

# Remove Templates
RUN rm -f core-site.xml hdfs-site.xml hadoop-env.sh mapred-site.xml yarn-site.xml

# Install Hadoop Config
ADD core-site.xml core-site.xml
ADD hdfs-site.xml hdfs-site.xml
ADD hadoop-env.sh hadoop-env.sh
ADD mapred-site.xml mapred-site.xml
ADD yarn-site.xml yarn-site.xml

# Install Hive config
ADD tez-site.xml /usr/local/apache-tez-0.9.0-bin/conf/tez-site.xml
ADD hive-site.xml /usr/local/apache-hive-2.3.3-bin/conf/hive-site.xml

# Set Env
ENV TEZ_CONF_DIR=/usr/local/apache-tez-0.9.0-bin/conf
ENV TEZ_JARS=/usr/local/apache-tez-0.9.0-bin/*:/usr/local/apache-tez-0.9.0-bin/lib/*
ENV HADOOP_CLASSPATH=$TEZ_CONF_DIR:$TEZ_JARS
ENV HADOOP_HOME=/usr/local/hadoop-2.7.6
ENV HIVE_HOME=/usr/local/apache-hive-2.3.3-bin
ENV PATH=$HIVE_HOME/bin:$HADOOP_HOME/bin:$PATH
ENV HADOOP_CLIENT_OPTS=-Xmx2048m

# Setup SSH
WORKDIR /root
RUN mkdir .ssh
RUN cat /dev/zero | ssh-keygen -q -N "" > /dev/null && cat /root/.ssh/id_rsa.pub > /root/.ssh/authorized_keys
RUN service ssh start && \
  ssh-keyscan localhost > /root/.ssh/known_hosts && \
  ssh-keyscan 0.0.0.0 >> /root/.ssh/known_hosts

# Format HFS
RUN /usr/local/hadoop-2.7.6/bin/hdfs namenode -format -nonInteractive

# Create Hive Metastore
RUN /usr/local/apache-hive-2.3.3-bin/bin/schematool -initSchema -dbType derby

# Copy tables.sql
ADD tables.sql /tmp/tables.sql
ADD start.sh /root/start.sh

# Hadoop Resource Manager
EXPOSE 8088

# Hadoop NameNode
EXPOSE 50070

# Hadoop DataNode
EXPOSE 50075

# Hive WebUI
EXPOSE 10002

# Hive Master
EXPOSE 10000

# Hive Metastore
EXPOSE 9083

# Start sshd, allow ssh connection for pseudo distributed mode, yarn, datanode and namenode, hive2 - move this to docker compose.
ENTRYPOINT /root/start.sh && tail -f /tmp/root/hive.log
