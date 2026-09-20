#!/usr/bin/env bash

set -euo pipefail

UNIT="lab1-mydocker.service"
LAB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

sudo systemctl stop "$UNIT" >/dev/null 2>&1 || true
sudo systemctl reset-failed "$UNIT" >/dev/null 2>&1 || true

sudo systemd-run \
  --unit=lab1-mydocker \
  --property=User=anna \
  --property=MemoryMax=300M \
  --property=MemorySwapMax=0 \
  --property=CPUQuota=50% \
  --property=TasksMax=64 \
  --property='CapabilityBoundingSet=~CAP_SYS_TIME' \
  --property='SystemCallFilter=~clock_settime' \
  --property=SystemCallErrorNumber=EPERM \
  --working-directory="$LAB_DIR" \
  /usr/bin/unshare \
    --user --map-root-user \
    --pid --fork \
    --mount --mount-proc \
    --net \
    --uts \
    --ipc \
    /bin/bash -c '
      hostname lab1-container
      /usr/sbin/ip link set lo up
      exec /usr/bin/java -jar lib/lab1-1.0.0.jar
    '

CG=""

for i in {1..20}; do
  CG=$(sudo systemctl show -p ControlGroup --value "$UNIT")

  if [[ -n "$CG" && -f "/sys/fs/cgroup${CG}/cgroup.procs" ]]; then
    break
  fi

  sleep 1
done

PID=""

PID=""

for i in {1..30}; do
  while read -r pid; do
    if [[ -r "/proc/$pid/comm" ]] && [[ "$(cat "/proc/$pid/comm")" == "java" ]]; then
      PID="$pid"
      break
    fi
  done < "/sys/fs/cgroup${CG}/cgroup.procs"

  [[ -n "$PID" ]] && break

  sleep 1
done

if [[ -z "$PID" ]]; then
  echo "Java process not found"
  sudo journalctl -u "$UNIT" -n 30 --no-pager
  exit 1
fi

echo
echo "Java host PID: $PID"

echo
echo "PID namespace:"
grep NSpid "/proc/$PID/status"

echo
echo "UTS namespace:"
sudo nsenter -t "$PID" -u hostname

echo
echo "User namespace:"
ps -o pid,user,uid,cmd -p "$PID"

echo
echo "Cgroup:"
cat "/proc/$PID/cgroup"

echo
echo "Memory limit:"
cat "/sys/fs/cgroup${CG}/memory.max"

echo
echo "CPU limit:"
cat "/sys/fs/cgroup${CG}/cpu.max"

echo
echo "PIDs limit:"
cat "/sys/fs/cgroup${CG}/pids.max"

echo
echo "Waiting for /health..."

for i in {1..30}; do
  if sudo nsenter -t "$PID" -n curl -fsS http://127.0.0.1:8080/health 2>/dev/null; then
    echo
    echo "Service is healthy"
    exit 0
  fi

  sleep 1
done

echo "Health check failed"
sudo journalctl -u "$UNIT" -n 30 --no-pager
exit 1