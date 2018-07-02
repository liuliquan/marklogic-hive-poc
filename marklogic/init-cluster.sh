#!/bin/bash

# Init cluster

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
source "$DIR/config.sh"

N_RETRY=60
RETRY_INTERVAL=1

# restart_check(admin_api_path, baseline_timestamp)
#
# Use the timestamp service to detect a server restart, given a
# a baseline timestamp. Use N_RETRY and RETRY_INTERVAL to tune
# the test length. Include authentication in the curl command
# so the function works whether or not security is initialized.
#   $1 :  The admin api path
#   $2 :  The baseline timestamp
# Returns 0 if restart is detected, exits with an error if not.
function restart_check {
  LAST_START=`$AUTH_CURL "$1/timestamp"`
  for i in `seq 1 $N_RETRY`; do
    if [ "$2" == "$LAST_START" ] || [ "$LAST_START" == "" ]; then
      sleep $RETRY_INTERVAL
      LAST_START=`$AUTH_CURL "$1/timestamp"`
    else 
      return 0
    fi
  done
  echo "ERROR: Failed to restart $1"
  exit 1
}

echo "Bootstrap host: $BOOTSTRAP_HOST"

BOOTSTRAP_ADMIN_API=`admin_api $BOOTSTRAP_HOST`

# Setup bootstrap host in cluster
echo "Use $BOOTSTRAP_ADMIN_API to admin $BOOTSTRAP_HOST"
echo "Initializing host: $BOOTSTRAP_HOST..."
TIMESTAMP=`$AUTH_CURL -X POST \
  -H "Content-type: application/json" -d "$LICENSE" \
  $BOOTSTRAP_ADMIN_API/init \
  | grep "last-startup" \
  | sed 's%^.*<last-startup.*>\(.*\)</last-startup>.*$%\1%'`
if [ -n "$TIMESTAMP" ]; then
  restart_check $BOOTSTRAP_ADMIN_API $TIMESTAMP
fi
echo "Successfully initialized $BOOTSTRAP_HOST"

echo "Configuring $BOOTSTRAP_HOST security..."
TIMESTAMP=`$AUTH_CURL -X POST \
   -H "Content-type: application/x-www-form-urlencoded" \
   --data "admin-username=$USER" --data "admin-password=$PASS" \
   --data "realm=public" \
   $BOOTSTRAP_ADMIN_API/instance-admin \
   | grep "last-startup" \
   | sed 's%^.*<last-startup.*>\(.*\)</last-startup>.*$%\1%'`
if [ -n "$TIMESTAMP" ]; then
  restart_check $BOOTSTRAP_ADMIN_API $TIMESTAMP
fi
echo "Successfully configured $BOOTSTRAP_HOST security"
echo "Bootstrap host done: $BOOTSTRAP_HOST"

# Join hosts to cluster
for JOINING_HOST in $JOIN_HOSTS; do
  JOINING_ADMIN_API=`admin_api $JOINING_HOST`

  echo "Join host: $JOINING_HOST"

  # (1) Initialize MarkLogic Server on the joining host
  echo "Use $JOINING_ADMIN_API to admin $JOINING_HOST"
  echo "Initializing host: $JOINING_HOST..."
  TIMESTAMP=`$AUTH_CURL -X POST -H "Content-type: application/json" -d "$LICENSE" \
     $JOINING_ADMIN_API/init \
     | grep "last-startup" \
     | sed 's%^.*<last-startup.*>\(.*\)</last-startup>.*$%\1%'`
  if [ -n "$TIMESTAMP" ]; then
    restart_check $JOINING_ADMIN_API $TIMESTAMP
  fi
  echo "Successfully initialized $JOINING_HOST"

  # (2) Retrieve the joining host's configuration
  echo "Fetch $JOINING_HOST server config"
  JOINER_CONFIG=`$AUTH_CURL -X GET -H "Accept: application/xml" \
        $JOINING_ADMIN_API/server-config`
  echo $JOINER_CONFIG | grep -q "^<host"
  if [ "$?" -ne 0 ]; then
    echo "ERROR: Failed to fetch server config for $JOINING_HOST"
    exit 1
  fi

  # (3) Send the joining host's config to the bootstrap host, receive
  #     the cluster config data needed to complete the join. Save the
  #     response data to cluster-config.zip.
  echo "Fetch $JOINING_HOST cluster config"
  $AUTH_CURL -X POST -o cluster-config.zip -d "group=Default" \
        --data-urlencode "server-config=$JOINER_CONFIG" \
        -H "Content-type: application/x-www-form-urlencoded" \
        $BOOTSTRAP_ADMIN_API/cluster-config
  if [ "$?" -ne 0 ]; then
    echo "ERROR: Failed to fetch cluster config from $BOOTSTRAP_ADMIN_API"
    exit 1
  fi
  if [ `file cluster-config.zip | grep -cvi "zip archive data"` -eq 1 ]; then
    echo "ERROR: Failed to fetch cluster config from $BOOTSTRAP_ADMIN_API"
    exit 1
  fi

  # (4) Send the cluster config data to the joining host, completing the join sequence.
  echo "Adding $JOINING_HOST to cluster..."
  TIMESTAMP=`$AUTH_CURL -X POST -H "Content-type: application/zip" \
      --data-binary @./cluster-config.zip \
      $JOINING_ADMIN_API/cluster-config \
      | grep "last-startup" \
      | sed 's%^.*<last-startup.*>\(.*\)</last-startup>.*$%\1%'`
  if [ -n "$TIMESTAMP" ]; then
    restart_check $JOINING_ADMIN_API $TIMESTAMP
  fi
  rm ./cluster-config.zip

  echo "Successfully added $JOINING_HOST to cluster"
  echo "Join host done: $JOINING_HOST"
done