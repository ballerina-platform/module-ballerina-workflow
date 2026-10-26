#!/usr/bin/env bash
# Runs the scenarios (all, or those named as arguments) against the running service, answers the reviews and
# human tasks they raise, and writes a report to results/<timestamp>.md.
set -u

BASE=${BASE:-http://127.0.0.1:8095/tests}
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

# Answers the tasks and reviews raised since the scenario started. An item is marked handled only once its
# answer is accepted, so a failed answer is tried again on the next poll.
answer_pending() {
    local scenario=$1 since=$2 pending task review
    pending=$(curl -sf -G "$BASE/pending" --data-urlencode "since=$since") || return
    for task in $(echo "$pending" | jq -r '.tasks[]?.taskId'); do
        case "$handled" in *" $task "*) continue ;; esac
        curl -sf -X POST "$BASE/tasks/$task" -H 'Content-Type: application/json' \
            -d '{"approved": true, "comment": "Approved: the customer travels on Friday"}' > /dev/null || continue
        handled="$handled$task "
        actions="${actions}task approved; "
    done
    for review in $(echo "$pending" | jq -r '.reviews[]?.taskId'); do
        case "$handled" in *" $review "*) continue ;; esac
        if [ "$scenario" = "C03-approval-rejected" ]; then
            decision='{"action": "reject", "feedback": "Refunds above 500 need a support ticket"}'
            done_text="review rejected"
        else
            decision='{"action": "proceed", "feedback": "Approved"}'
            done_text="review approved"
        fi
        curl -sf -X POST "$BASE/reviews/$review" -H 'Content-Type: application/json' -d "$decision" > /dev/null \
            || continue
        handled="$handled$review "
        actions="${actions}${done_text}; "
    done
}

for scenario in $names; do
    actions=""
    began=$(date +%s)
    started=$(curl -s -X POST "$BASE/run/$scenario")
    id=$(echo "$started" | jq -r '.id // empty' 2>/dev/null)
    since=$(echo "$started" | jq -r '.started // empty' 2>/dev/null)
    if [ -z "$id" ]; then
        echo "| $scenario | START_FAILED | 0 | | $(echo "$started" | tr '\n|' '  ' | cut -c1-300) |" >> "$report"
        echo "$scenario: START_FAILED"
        continue
    fi
    if [ "$scenario" = "C06-chat" ]; then
        for message in "Is the laptop in stock?" "Thanks, goodbye."; do
            # Bounded by TIMEOUT: a reply that never comes would otherwise block the whole run.
            reply=$(curl -s --max-time "$TIMEOUT" -X POST "$BASE/chat/$id" -H 'Content-Type: text/plain' \
                -d "$message") || { actions="${actions}chat: no reply within ${TIMEOUT}s; "; break; }
            actions="${actions}chat: $(echo "$reply" | jq -r '.reply // .message'); "
        done
    fi
    status=IN_PROGRESS
    while [ $(( $(date +%s) - began )) -lt "$TIMEOUT" ]; do
        answer_pending "$scenario" "$since"
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
