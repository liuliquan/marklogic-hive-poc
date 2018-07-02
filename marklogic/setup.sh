#!/bin/bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
source "$DIR/init-cluster.sh"
source "$DIR/create-db.sh"
echo "Setup done"