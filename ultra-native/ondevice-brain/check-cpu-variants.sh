#!/usr/bin/env bash
# Proves each libultra_llm variant contains the CPU instructions it is named for, and the
# armv8-a fallback contains none of them (a variant that silently lost its -march would
# still link and run, just slowly). Usage: check-cpu-variants.sh <dir with the .so files>
# Needs ANDROID_NDK (for llvm-objdump).
set -euo pipefail
DIR="${1:?dir with libultra_llm*.so}"
: "${ANDROID_NDK:?set ANDROID_NDK}"
OBJDUMP="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"

count() {  # <lib> <mnemonic>: how many times the mnemonic appears in its disassembly
  "$OBJDUMP" -d --no-show-raw-insn --mattr=+dotprod,+i8mm,+fullfp16 "$DIR/$1" \
    | awk -v m="$2" '$2 == m' | wc -l
}

bad=0
expect() {  # <lib> <mnemonic> <present|absent>
  local n; n=$(count "$1" "$2")
  echo "$1: $2 x$n (want $3)"
  if [ "$3" = present ] && [ "$n" -eq 0 ]; then bad=1; fi
  if [ "$3" = absent ] && [ "$n" -ne 0 ]; then bad=1; fi
}

for lib in libultra_llm.so libultra_llm_dotprod.so libultra_llm_i8mm.so; do
  [ -f "$DIR/$lib" ] || { echo "missing $DIR/$lib"; bad=1; }
done
[ $bad -eq 0 ] || exit 1

expect libultra_llm.so         sdot  absent
expect libultra_llm.so         smmla absent
expect libultra_llm_dotprod.so sdot  present
expect libultra_llm_dotprod.so smmla absent
expect libultra_llm_i8mm.so    sdot  present
expect libultra_llm_i8mm.so    smmla present

[ $bad -eq 0 ] || { echo "a CPU variant is missing its instructions"; exit 1; }
echo "CPU variants OK"
