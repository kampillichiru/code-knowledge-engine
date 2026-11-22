# Conformance fixtures

Source files that contain every Java construct the engine must handle, with expected results written by hand.
They are test inputs, not part of the build.

- `probe/src/probe/Probe.java`: about 35 constructs (guard clauses, implicit else, loops, try/catch/finally, old and new
  switch, pattern switch with guards, short-circuit conditions, lambdas, anonymous classes, varargs, collections, labeled
  break/continue, assert, synchronized, static and field initialisers, records, enums, inner classes).
- `golden/probe-outcomes.txt`: the number of decision outcomes per method, counted by hand. The syntax census must match it
  exactly (59 in total).

The same probe also measures how much of that the rest of the engine captures as rules: see `cli` `ConformanceTest`.
That number is a ratchet: it may only go up.

## Step 3 fixture

- `probe2/src/probe2/Step3.java`: constructs outside plain method bodies or in expression position: a static initialiser block, an
  instance initialiser block, a field initialiser with a ternary, a lambda with a boolean body (a predicate) and one with a block, a switch
  expression as a value (a declaration with a `yield` inside an `if`), as a call argument and as an assignment, and a pattern `switch`
  statement with a `when` guard.
- `golden/step3-outcomes.txt` (26 outcomes, counted by hand) and `golden/step3-paths.txt` (the paths of the switch methods, derived
  by hand from the Java semantics).

`golden/probe-paths.txt` also holds the paths of the probe's switch expressions (`switchNew`, `switchYield`, `patternSwitch`).
