#!/bin/sh
set -eu

topic=post-interaction-topic
nameserver=${NAMESRV_ADDR:?NAMESRV_ADDR is required}

for attempt in 1 2 3 4 5; do
    route=$(sh mqadmin topicRoute -n "$nameserver" -t "$topic" -l 2>&1) || true

    if printf '%s\n' "$route" | grep -Eq '^Total:[[:space:]]+1[[:space:]]+4[[:space:]]+4[[:space:]]*$' &&
       printf '%s\n' "$route" | grep -Eq '^DefaultCluster[[:space:]]+broker-a[[:space:]]+.+[[:space:]]+4[[:space:]]+4[[:space:]]+6[[:space:]]*$'; then
        printf '%s\n' "$topic is ready with 4 read and 4 write queues."
        exit 0
    fi

    if printf '%s\n' "$route" | grep -q '^Total:'; then
        printf '%s\n' "Incompatible $topic route. Existing configuration was preserved." "$route" >&2
        exit 1
    fi

    if [ "$attempt" -eq 1 ] && printf '%s\n' "$route" | grep -q 'CODE: 17'; then
        sh mqadmin updateTopic -n "$nameserver" -b rocketmq-broker:10911 -t "$topic" -r 4 -w 4
    fi

    sleep 2
done

printf '%s\n' "Unable to confirm $topic route." "$route" >&2
exit 1
