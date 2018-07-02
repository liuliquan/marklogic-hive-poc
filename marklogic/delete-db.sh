#!/bin/bash

# Delete database and tiers

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
source "$DIR/config.sh"

echo "Use $MANAGE_API to manage database"

echo "Deleting tier1 partition..."
$AUTH_CURL -X DELETE $MANAGE_API/databases/$DB_NAME/partitions/tier1?delete-data=true
echo "Successfully deleted tier1 partition"

echo "Deleting $DB_NAME database..."
$AUTH_CURL -X DELETE $MANAGE_API/databases/$DB_NAME
echo "Successfully deleted $DB_NAME database"
