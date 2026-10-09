// Research-only arm64 simulator ABI bridge. The audited native getter accepts
// its Swift value in x20 and returns Foundation.Data in x0/x1. No transport.
.text
.p2align 2
.globl _probeNativeReplicatorHeaderData
_probeNativeReplicatorHeaderData:
    stp x20, x30, [sp, #-16]!
    mov x20, x0
    blr x1
    ldp x20, x30, [sp], #16
    ret
