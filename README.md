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



When you see something like following about Tez session initialized, the containers are all started:

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

# The setup takes about 1 minute. It initializes the MarkLogic Servers servers, creates a new database and SQL views.
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



## Load CSV Records into Kafka

Run:

```bash
docker cp ./sample-data/Transaction logstash.local:/usr/share/logstash/data/Transaction
docker cp ./sample-data/Instrument logstash.local:/usr/share/logstash/data/Instrument
docker cp ./sample-data/Position logstash.local:/usr/share/logstash/data/Position

# Logstash will parse the sample csv files and insert into kafka
# The load will take about 1 minute.
```



To verify the csv files are loaded, go to http://localhost:9600/_node/stats/pipelines?pretty , when you see **1368394** events output to "kafka" (Like this: http://take.ms/n37pc), then this load step is done. 



## Load Kafka Records into MarkLogic

Run:

```bash
docker exec kafka.local /opt/kafka_2.11-0.10.1.0/bin/connect-standalone.sh /config/kafka-connect-standalone.properties /config/kafka-sinker.properties

# The connector will consume kafka records and insert into MarkLogic
# The load will take about 2 minutes.
```



When you see following log which saying the **`account` topic has offset 7611, the `instrument` and `position` topics both have offset 20000**, then the load is finished:

```properties
INFO Flush - Topic position, Partition 0, Offset 20000, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:105)
INFO Flush - Topic instrument, Partition 0, Offset 20000, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:105)
INFO Flush - Topic account, Partition 0, Offset 7611, Metadata  (kafka.connect.marklogic.sink.MarkLogicSinkTask:105)
```



**TIP**: after this step, the logstash and kafka containers are useless now, you can stop them to save cpu/memory resources:

```bash
docker stop logstash.local
docker stop kafka.local
```



## Verification

Verify data are stored in 3 tiers by checking the storage size. Use `admin/admin` to login:

Tier1: http://localhost:8002/manage/v2/forests/tier1-0001?view=storage&format=html , should be like [this](http://take.ms/vYQHc)

Tier2: http://localhost:8002/manage/v2/forests/tier2-0001?view=storage&format=html , should be like [this](http://take.ms/YL01Z9)

Tier3: http://localhost:8002/manage/v2/forests/tier3-0001?view=storage&format=html , should be like [this](http://take.ms/xNbPx)



Verify the query performance:

```bash
time ./marklogic/query.sh account tier1
time ./marklogic/query.sh account tier2
time ./marklogic/query.sh account tier3

time ./marklogic/query.sh instrument tier1
time ./marklogic/query.sh instrument tier2
time ./marklogic/query.sh instrument tier3

time ./marklogic/query.sh position tier1
time ./marklogic/query.sh position tier2
time ./marklogic/query.sh position tier3
```



Import `test/tiers-poc.postman_collection.json` and  `test/tiers-poc.postman_environment.json` into Postman, you can do following:

- Count/Search `account` records
- Count/Search `account` records in specific Tier1/Tier2/Tier3
- Count/Search `instrument` records
- Count/Search `instrument` records in specific Tier1/Tier2/Tier3
- Count/Search `position` records
- Count/Search `position` records in specific Tier1/Tier2/Tier3



Sample Postman screenshot which searches `position` records in Tier3: http://take.ms/PVBf8