#!/usr/bin/env bash

# usage: throughput.sh <host> <port>

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

host=$1
port=$2

exec tcpkali -m '$' $host:$port
