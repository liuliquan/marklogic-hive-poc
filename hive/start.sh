#!/bin/bash

service ssh start

ssh-keyscan hive.local >> /root/.ssh/known_hosts

/usr/local/hadoop-2.7.6/sbin/start-yarn.sh

/usr/local/hadoop-2.7.6/sbin/start-dfs.sh

nohup /usr/local/apache-hive-2.3.3-bin/bin/hive --service metastore >/var/log/metastore.log 2>/var/log/metastore.log &

# Wait metastore starts
sleep 10

nohup /usr/local/apache-hive-2.3.3-bin/bin/hive --service hiveserver2 >/var/log/hiveserver2.log 2>/var/log/hiveserver2.log &