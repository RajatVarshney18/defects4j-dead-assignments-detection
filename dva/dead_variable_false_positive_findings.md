# Dead Variable Analysis Findings

This note records the false-positive patterns observed in the current Jimple-based dead-variable pass.

## 1. `JFreeChart.<init>(String, Font, Plot, boolean)`

- Reported Jimple statement: `legend = $stack20`
- Why it is reported: the assignment is a copy from a Jimple stack temporary, and later uses in the body still reference `$stack20` directly.
- Why it is a false positive at source level: the Java source variable `legend` is used as part of the legend construction flow, but Jimple has already split the object creation and aliasing into separate IR statements.
- Current classification: dead copy in IR, but source-intent false positive.

## 2. `SymbolAxis.autoAdjustRange()`

- Reported Jimple statements:
  - `upperMargin = 0.5`
  - `lowerMargin = 0.5`
- Why it is reported: the Jimple body contains direct uses of literal `0.5` in later branches instead of reusing the `upperMargin` and `lowerMargin` locals.
- Why it is a false positive at source level: the Java source uses those locals in branch logic, but the compiled/Jimple form has already constant-folded or inlined the value in the later expressions.
- Current classification: dead constant assignment in IR, but source-intent false positive.

## 3. `AxisSpace.hashCode()`

- Reported Jimple statement: `result#0 = 23`
- Why it is reported: the initial hash seed is assigned to a local, but Jimple folds the first multiplication into the later expression, so the seed local itself is never read.
- Why it is a false positive at source level: the `23` seed is part of the hash-code formula in Java source and contributes to the final result through constant folding.
- Current classification: dead constant assignment in IR, but source-intent false positive.

## 4. Message formatting issue in dead-copy reports

- Observed issue: dead-copy reasons were printed with an extra `$`, producing strings like `$$stack20`.
- Why it matters: it makes the report harder to read and can make IR names look more suspicious than they are.
- Status: formatting issue only; separate from the liveness logic.

## 5. General pattern behind these false positives

- The analysis is running on compiled Jimple, not Java source.
- Compiler and Jimple transformations can introduce alias copies, temporary locals, and constant folding that do not map cleanly back to source-level variable usage.
- As a result, a statement can look dead in IR even when the source-level construct is meaningful.

## 6. Practical takeaway

- Alias copies from stack temporaries and constant-inlined locals are the main source of noise seen so far.
- If the goal is source-level dead-variable reporting, these patterns need to be treated as IR artefacts rather than real findings.

## 7. `SegmentedTimeline.containsDomainRange(long,long)`

- Reported Jimple statement: `contains#0 = 1`
- Why it is reported: the initial value is immediately overwritten by `contains = segment.inIncludeSegments()` at the top of the `do` loop before any read of the initial assignment.
- Source-level interpretation: this appears to be a true dead initialization, not just an IR artefact.
- Behavioral note: removing only the initializer `boolean contains = true;` should not change behavior because `contains` is always assigned inside the first loop iteration before it is tested in `while (contains)`.

## 8. `WaferMapRenderer.getLegendCollection()`

- Reported Jimple statements:
  - `description#0 = label#0`
  - `shape#1 = description#1`
- Why they are reported: in compiled Jimple, constructor arguments are often passed directly from temporaries (`label#0`, `$stack47`, etc.), so alias locals such as `description` and `shape` can become unread copies.
- Important caveat: this method also shows local-name/type remapping noise in Jimple (for example, `shape#1 = description#1` and later `paint#1 = (java.awt.geom.Rectangle2D$Double) $stack37`), which indicates local debug-name drift rather than faithful source variable identity.
- Source-level interpretation: primarily an IR/local-debug mapping artefact plus copy-propagation; not a reliable source-level dead-variable bug by itself.

## 9. `StandardBarPainter.hashCode()`

- Source form:
  - `int hash = 37;`
  - `return hash;`
- Jimple form observed:
  - `hash = 37`
  - `return 37`
- Why it is reported: the compiler/Jimple pass constant-folded the return value to a literal, so the local `hash` assignment is no longer read.
- Source-level interpretation: this is not a meaningful bug pattern; it is a trivial constant-return folding artefact.

## 10. `DefaultIntervalCategoryDataset.<init>(...)` error-message locals

- Reported Jimple statements:
  - `errMsg#0 = "DefaultIntervalCategoryDataset: the number of series ..."`
  - `errMsg_1#0 = "DefaultIntervalCategoryDataset: the number of categories ..."`
- Why they are reported: Jimple shows the throw-site calling `IllegalArgumentException.<init>(String)` with the same literal directly, instead of reading the local variable.
- Evidence in Jimple:
  - local assignment at index 14 / 44
  - constructor call uses literal at index 17 / 47
- Source-level interpretation: likely compiler/source-mapping optimization artifact; in source, `errMsg` is used in `throw new IllegalArgumentException(errMsg)`.