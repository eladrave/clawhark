#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_test_dir=$(mktemp -d)
trap 'rm -rf "$task_test_dir"' EXIT
java com.sun.tools.javac.Main -d "$task_test_dir" app/src/main/java/com/ettlinger/wearrecorder/RecordingStore.java app/src/main/java/com/ettlinger/wearrecorder/AdtsRecovery.java tests/RecordingStoreTest.java
java -cp "$task_test_dir" com.ettlinger.wearrecorder.RecordingStoreTest
