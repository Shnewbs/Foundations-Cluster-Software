# R7.R4 Test Plan

## Reference self-tests

Windows:

```bat
tools\compile_and_test_reference.bat
```

Linux:

```bash
./tools/compile_and_test_reference.sh
```

The harness tests 100 bidirectional filesystem transfers, stale destination replacement, rollback restoration, custom dimensions, and the post-CAS split-brain guard.

## Live Minecraft test

Use a disposable test world first.

1. Confirm both workers are healthy.
2. Confirm the target authority regions show ACTIVE before starting.
3. On Worker 1, place a distinctive block pattern inside one authority region.
4. Put named items in a chest in that region.
5. Transfer that region to Worker 2.
6. Confirm terrain does not visibly revert and the block pattern/chest remain.
7. Change the pattern and chest contents on Worker 2.
8. Transfer the same region back to Worker 1.
9. Confirm Worker 1 receives the newer Worker 2 state.
10. Repeat at least 25 times.

Then repeat with:

- furnace/block entity state;
- persistent named mob;
- TFC crop/block state;
- one custom/modded dimension if available.

## Failure injection

Test with disposable data only:

- stop destination before authority CAS -> source must remain active;
- stop source after snapshot but before CAS -> manager must not invent newer state;
- simulate destination activation failure after CAS -> old source must remain fenced and region must enter RECOVERING;
- corrupt staged snapshot -> checksum failure must prevent authority commit;
- restart manager with unfinished transfer journal -> automatic ownership must remain conservative until recovery is resolved.

## Pass condition

R7.R4 passes when world state follows the authority region in both directions without stale terrain resurrection and no failure path can create two writable owners.
