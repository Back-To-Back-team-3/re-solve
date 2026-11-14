#!/bin/sh
set -eu

cgroup=/sys/fs/cgroup
if [ "$(cat /proc/self/cgroup)" != '0::/' ] \
    || [ "$(stat -fc %T "$cgroup")" != cgroup2fs ] \
    || [ ! -w "$cgroup/cgroup.subtree_control" ]; then
    echo 'Judge0 requires a writable private cgroup v2 namespace.' >&2
    exit 1
fi

# 워커 프로세스를 하위 그룹으로 이동한 뒤 샌드박스에 필요한 컨트롤러를 활성화한다.
mkdir -p "$cgroup/worker" /run/isolate/locks
printf '%s\n' "$$" > "$cgroup/worker/cgroup.procs"
printf '%s\n' '+cpuset +memory' > "$cgroup/cgroup.subtree_control"

exec setpriv --reuid=judge0 --regid=judge0 --init-groups /api/docker-entrypoint.sh "$@"
