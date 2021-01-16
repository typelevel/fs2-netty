#!/usr/bin/env bash

# usage: rps.sh <host> <port>

set -euo pipefail  # STRICT MODE
IFS=$'\n\t'        # http://redsymbol.net/articles/unofficial-bash-strict-mode/

host=$1
port=$2

exec echo_bench --address "$host:$port" --number 200 --duration 30 --length 128
