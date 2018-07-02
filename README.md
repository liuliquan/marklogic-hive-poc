## Prerequisite

Java 1.8

Maven 3

Docker (**The Docker host machine should be with 16G memory or more**)

Postman for verification

## Download MarkLogic

Download [MarkLogic Server x64 (AMD64, Intel EM64T) 64-bit Linux RPM](https://developer.marklogic.com/products) and save it into `marklogic` folder.

In `marklogic/Dockerfile`  line 17, make sure the rpm file name is same as the downloaded file, currently it is *MarkLogic-9.0-6.x86_64.rpm*.



## Build

Build kafka sinker:

```bash
cd kafka-sinker
mvn clean package -Dmaven.test.skip=true
```

Build docker containers:

```bash
cd ..
docker-compose build
```



## Start Docker Containers

Run:

```bash
docker-compose up -d

# Wait about 2 minutes
# Then check the Tez session of "hive.local" is initialized
docker exec hive.local cat /tmp/root/hive.log | grep TezSession
```



When you see something like following about Tez session created, the containers are all started:

```properties
2018-07-02T06:45:41,950  INFO [main] tez.TezSessionPoolManager: Created new tez session for queue: default with session id: 704d121b-70a3-4ddc-a024-fdb7fcf6e3e1
2018-07-02T06:45:45,217  INFO [main] tez.TezSessionState: User of session id 704d121b-70a3-4ddc-a024-fdb7fcf6e3e1 is root
2018-07-02T06:45:47,102  INFO [main] tez.TezSessionState: Opening new Tez Session (id: 704d121b-70a3-4ddc-a024-fdb7fcf6e3e1, scratch dir: hdfs://hive.local:9000/tmp/hive/root/_tez_session_dir/704d121b-70a3-4ddc-a024-fdb7fcf6e3e1)
2018-07-02T06:45:48,791  INFO [main] tez.TezSessionState: Prewarming 1 containers  (id: 704d121b-70a3-4ddc-a024-fdb7fcf6e3e1, scratch dir: hdfs://hive.local:9000/tmp/hive/root/_tez_session_dir/704d121b-70a3-4ddc-a024-fdb7fcf6e3e1)
2018-07-02T06:45:56,684  INFO [main] client.TezClient: Submitting dag to TezSession, sessionName=HIVE-704d121b-70a3-4ddc-a024-fdb7fcf6e3e1, applicationId=application_1530513907233_0001, dagName=TezPreWarmDAG_0
2018-07-02T06:45:56,941  INFO [main] client.TezClient: Submitted dag to TezSession, sessionName=HIVE-704d121b-70a3-4ddc-a024-fdb7fcf6e3e1, applicationId=application_1530513907233_0001, dagId=dag_1530513907233_0001_1, dagName=TezPreWarmDAG_0
```



## Setup MarkLogic

Config is in `./marklogic/config.sh`, you can config following:

- USER/PASS: The admin username/password, which will be automatically created
- DB_NAME: The name of database, which will be automatically created
- BOOTSTRAP_HOST: Bootstrap host in cluster
- JOIN_HOSTS: Hosts to join to cluster
- TIER1_HOSTS/TIER2_HOSTS/TIER3_HOSTS: You can config the hosts the tier to be stored in
- HDFS_PATH: The HDFS path for tier3 storage
- MANAGE_API: Manage api path
- SEARCH_API: Search api path
- admin_api: Admin api path
- LICENSE: The marklogic license



Run:

```bash
./marklogic/setup.sh

# The setup takes about 1 minute. It initializes the MarkLogic Server, creates a new database and SQL views.
```

You will see output like following, note the "*curl: (52) Empty reply from server*" message is expected as the server is initializing:

```properties
Bootstrap host: ml9node1.local
Use http://localhost:8001/admin/v1 to admin ml9node1.local
Initializing host: ml9node1.local...
curl: (52) Empty reply from server
Successfully initialized ml9node1.local
Configuring ml9node1.local security...
curl: (52) Empty reply from server
Successfully configured ml9node1.local security
Bootstrap host done: ml9node1.local
Use http://localhost:8002/manage/v2 to manage database
Creating database: tiers-poc...
Successfully created database: tiers-poc
Creating tier1 partition on hosts: [ "ml9node1.local" ] ...
Successfully created tier1 partition
Successfully created SQL views
Setup done
```



## Create Hive Tables

Run

```bash
# Create Hive tables
docker exec hive.local beeline -u jdbc:hive2://hive.local:10000/default -f /tmp/tables.sql

# Verify instrument/position/transaction tables created
docker exec hive.local beeline -u jdbc:hive2://hive.local:10000/default -e "show tables"

```



## Load CSV Records into Kafka

Download sample-data.zip from https://drive.google.com/open?id=1jYBqIDXN8QT4_C-wg4_X62fNXUE4RfBg

Unzip it to `sample-data` folder.

```bash
# With project dir, unzip <path-to-downloaded-sample-data.zip>, eg:
unzip ~/Downloads/sample-data.zip

# Logstash will then parse the sample CSV files and insert into kafka.
# The load will take about 5 minutes.
```



To verify the CSV files are loaded, go to http://localhost:9600/_node/stats/pipelines?pretty , when you see **1430312** events output to "kafka" (Like this: http://take.ms/4iEUn), then this load step is done. 



**TIP**: after this step, the logstash container is useless now, you can stop them to save cpu/memory resources:

```bash
docker stop logstash.local
```



## Load Kafka Records into MarkLogic

Run:

```bash
docker exec kafka.local /opt/kafka_2.11-0.10.1.0/bin/connect-standalone.sh /config/kafka-connect-standalone.properties /config/kafka-sink.properties

# The connector will consume kafka records and insert into MarkLogic
# The load will take about 10 minutes.
```



When you see following log which saying the **`position` topic has offset 51441, the `instrument` topic has offset 138872, the `transaction` topics both have offset 1239999**, then the load is finished:

```properties
[2018-07-02 11:00:51,449] INFO Flush - Topic position, Partition 0, Offset 51441, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:112)
[2018-07-02 11:00:51,449] INFO Flush - Topic instrument, Partition 0, Offset 138872, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:112)
[2018-07-02 11:00:51,449] INFO Flush - Topic transaction, Partition 0, Offset 1239999, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:112)
```



**TIP**: after this step, the kafka container is useless now, you can stop them to save cpu/memory resources:

```bash
docker stop kafka.local
```



## Start Query Service

Run:

```bash
cd query-service
mvn compile exec:java
```



## Verification



Import `test/tiers-poc.postman_collection.json` and  `test/tiers-poc.postman_environment.json` into Postman, you can do following:

- Count/Search `transaction` records
- Count/Search `instrument` records
- Count/Search `position` records

