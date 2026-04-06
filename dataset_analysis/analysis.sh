#!/bin/bash

for i in $(seq 1 48); do
    buggy_src=$(find ~/Documents/IPACO/Project2/dataset_analysis/d4j_bugs/Compress/Compress_${i}_buggy/src -name "*.java" 2>/dev/null)
    for f in $buggy_src; do
        fixed="${f/Compress_${i}_buggy/Compress_${i}_fixed}"
        if [ -f "$fixed" ]; then
            changed=$(diff "$f" "$fixed" | grep "^[<>]" | wc -l)
            if [ "$changed" -gt 0 ] && [ "$changed" -le 6 ]; then
                echo "=== Compress Bug $i | Lines changed: $changed ==="
                diff "$f" "$fixed" | grep "^[<>]"
                echo ""
            fi
        fi
    done
done
