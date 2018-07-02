#!/bin/bash

# Setup database and tiers

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
source "$DIR/config.sh"

echo "Use $MANAGE_API to manage database"

# (1) Create database
echo "Creating database: $DB_NAME..."
DATABASE="
<database-properties>
  <database-name>$DB_NAME</database-name>
  <schema-database>Schemas</schema-database>
  <locking>strict</locking>
  <assignment-policy>
    <assignment-policy-name>query</assignment-policy-name>
    <default-partition>1</default-partition>
  </assignment-policy>
</database-properties>
"

$AUTH_CURL -X POST -H "Content-Type: application/xml" -d "$DATABASE" $MANAGE_API/databases
echo "Successfully created database: $DB_NAME"

# (2) Create tier1 partition
echo "Creating tier1 partition on hosts: $TIER1_HOSTS ..."
Tier1="{
  \"partition-name\": \"tier1\",
  \"partition-number\": \"1\",
  \"forests-per-host\": 1,
  \"host\": $TIER1_HOSTS,
  \"option\": [ \"failover=none\" ]
}"
$AUTH_CURL -X POST  -H "Content-Type: application/json" -d "$Tier1" $MANAGE_API/databases/$DB_NAME/partitions
echo "Successfully created tier1 partition"

# (3) Create SQL views
$AUTH_CURL -X POST -H "Content-type: application/x-www-form-urlencoded" -H "Accept: multipart/mixed; boundary=BOUNDARY" -d @$DIR/views.xqy $CLIENT_REST_API/eval?database=$DB_NAME
echo "Successfully created SQL views"