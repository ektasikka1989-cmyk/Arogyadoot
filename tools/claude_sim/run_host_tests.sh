#!/bin/bash
# usage: run.sh Test1 Test2 ...   (cwd: tools/host-tests)
K=$HOME/mnt/kotlinc
KCP="$K/lib/kotlin-compiler.jar:$K/lib/kotlin-stdlib.jar:$K/lib/kotlin-script-runtime.jar:$K/lib/kotlin-reflect.jar:$K/lib/trove4j.jar:$K/lib/kotlinx-coroutines-core-jvm.jar:$K/lib/annotations-13.0.jar"
LIBS="lib/json-20240303.jar:lib/kotlinx-coroutines-core-jvm-1.11.0.jar:lib/kotlin-stdlib-2.1.20.jar"
cd $HOME/mnt/Arogyadoot/tools/host-tests
for t in "$@"; do
  out=$HOME/ht/out/$t; rm -rf $out; mkdir -p $out
  java -Xmx1g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -Xfriend-paths=$HOME/ht/app.jar -cp "$HOME/ht/app.jar:$LIBS" -d $out "$t.kt" > $HOME/ht/$t.compile.log 2>&1
  if [ $? -ne 0 ]; then echo "== $t: COMPILE FAILED"; grep -v warning $HOME/ht/$t.compile.log | grep -A3 error | head -12; continue; fi
  java -cp "$out:$HOME/ht/app.jar:$LIBS" ${t}Kt > $HOME/ht/$t.run.log 2>&1; rc=$?
  echo "== $t rc=$rc: $(grep -E 'ALL [0-9]+ PASS|passed|FAILED|checks, [0-9]+ failures' $HOME/ht/$t.run.log | tail -1)"
  grep -E '^FAIL' $HOME/ht/$t.run.log | head -8
done
