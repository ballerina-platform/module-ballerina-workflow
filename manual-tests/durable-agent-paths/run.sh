#!/usr/bin/env bash
# Runs the scenarios (all, or those named as arguments) against the running service, answers the reviews and
# human tasks they raise, and writes a report to results/<timestamp>.md.
set -u

BASE=${BASE:-http://localhost:8095/tests}
TIMEOUT=${TIMEOUT:-240}
cd "$(dirname "$0")"
mkdir -p results
report="results/$(date +%Y%m%d-%H%M%S).md"
handled=" "

if [ $# -gt 0 ]; then
    names="$*"
else
    names=$(curl -sf "$BASE/scenarios" | jq -r '.[]') || { echo "Service not reachable at $BASE"; exit 1; }
fi

{
    echo "# Agent path tests $(date '+%Y-%m-%d %H:%M')"
    echo
    echo "| Scenario | Status | Seconds | Actions | Result or error |"
    echo "|---|---|---|---|---|"
} > "$report"

answer_pending() {
    local scenario=$1 pending task review
    pending=$(curl -s "$BASE/pending") || return
    for task in $(echo "$pending" | jq -r '.tasks[]?.taskId'); do
        case "$handled" in *" $task "*) continue ;; esac
        handled="$handled$task "
        curl -s -X POST "$BASE/tasks/$task" -H 'Content-Type: application/json' \
            -d '{"approved": true, "comment": "Approved: the customer travels on Friday"}' > /dev/null
        actions="${actions}task approved; "
    done
    for review in $(echo "$pending" | jq -r '.reviews[]?.taskId'); do
        case "$handled" in *" $review "*) continue ;; esac
        handled="$handled$review "
        if [ "$scenario" = "C03-approval-rejected" ]; then
            curl -s -X POST "$BASE/reviews/$review" -H 'Content-Type: application/json' \
                -d '{"action": "reject", "feedback": "Refunds above 500 need a support ticket"}' > /dev/null
            actions="${actions}review rejected; "
        else
            curl -s -X POST "$BASE/reviews/$review" -H 'Content-Type: application/json' \
                -d '{"action": "proceed", "feedback": "Approved"}' > /dev/null
            actions="${actions}review approved; "
        fi
    done
}

for scenario in $names; do
    actions=""
    began=$(date +%s)
    started=$(curl -s -X POST "$BASE/run/$scenario")
    id=$(echo "$started" | jq -r '.id // empty' 2>/dev/null)
    if [ -z "$id" ]; then
        echo "| $scenario | START_FAILED | 0 | | $(echo "$started" | tr '\n|' '  ' | cut -c1-300) |" >> "$report"
        echo "$scenario: START_FAILED"
        continue
    fi
    if [ "$scenario" = "C06-chat" ]; then
        for message in "Is the laptop in stock?" "Thanks, goodbye."; do
            reply=$(curl -s -X POST "$BASE/chat/$id" -H 'Content-Type: text/plain' -d "$message" | jq -r '.reply // .message')
            actions="${actions}chat: ${reply}; "
        done
    fi
    status=IN_PROGRESS
    while [ $(( $(date +%s) - began )) -lt "$TIMEOUT" ]; do
        answer_pending "$scenario"
        result=$(curl -s "$BASE/result/$scenario/$id")
        status=$(echo "$result" | jq -r '.status // "UNREADABLE"')
        [ "$status" = "IN_PROGRESS" ] || break
        sleep 2
    done
    detail=$(echo "$result" | jq -c 'if .status == "DONE" then .result else (.error // .) end' 2>/dev/null \
        | tr '\n|' '  ' | cut -c1-400)
    seconds=$(( $(date +%s) - began ))
    echo "| $scenario | $status | $seconds | ${actions% } | \`$detail\` |" >> "$report"
    echo "$scenario: $status (${seconds}s)"
done

echo
echo "Report: $report"
