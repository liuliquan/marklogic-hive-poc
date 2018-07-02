#!/bin/bash

# Admin user/password
USER=admin
PASS=admin

# Database name
DB_NAME=tiers-poc

# Bootstrap host in cluster
BOOTSTRAP_HOST=ml9node1.local

# Hosts to join to cluster
# JOIN_HOSTS="ml9node2.local ml9node3.local"
JOIN_HOSTS=""

# Tier1 will be stored in these hosts:
TIER1_HOSTS='[ "ml9node1.local" ]'
# Tier2 will be stored in these hosts:
# TIER2_HOSTS='[ "ml9node2.local" ]'
# To config multiple hosts, something like:
# TIER1_HOSTS='[ "ml9node1.local", "ml9node2.local" ]'

# Manage api path
MANAGE_API=http://localhost:8002/manage/v2

# Client rest api path
CLIENT_REST_API=http://localhost:8000/v1

# admin_api(host)
#
# Get the admin api path of host
#   $1 :  The host to get its admin api path
# Returns admin api path.
function admin_api {
    case $1 in
        'ml9node1.local') echo 'http://localhost:8001/admin/v1';;
        'ml9node2.local') echo 'http://localhost:18001/admin/v1';;
    esac
}

# You can request your own license at: http://localhost:8001/license.xqy
LICENSE='
{
  "licensee" : "liuliquan - Development",
  "license-key" : "B581-DCA7-6833-B98E-58F0-C866-533B-911D-686A-436A-53D0-7693-3D9D-EC87-6D33-F9DF-C086-1431-AD1D-09EB-FDA8-5000"
}'

# Curl alias
CURL="curl -s -S"
AUTH_CURL="$CURL --anyauth --user $USER:$PASS"