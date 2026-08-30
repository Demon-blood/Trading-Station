M12 apply-script v1.6

Compile-fix only. No trading/runtime behavior changes.

Root cause:
tools/apply_m12_order_truth_authority.py copied every new M12 payload file using:

    .rstrip() + "\\n"

At Python runtime that appends the literal characters backslash + n to the Kotlin
file, so files ended like:

    }
    \n

Kotlin/KSP then reported "Expecting a top level declaration" at each file's final line.

v1.6 changes the copier to append a real newline:

    .rstrip() + "\n"

and adds a guard that aborts the apply step if a generated file still ends with a
literal backslash+n.

Replace exactly:
tools/apply_m12_order_truth_authority.py

Commit to main, then launch a NEW M12 workflow from main.
Do not use Re-run jobs on the failed run.
