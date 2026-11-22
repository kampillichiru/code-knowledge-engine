# How it works

The goal: given an unknown Java codebase, say **where every output value comes from**, with evidence, and say honestly where it
cannot. This page explains how the engine gets there. To run it, see [HOW_TO_RUN.md](HOW_TO_RUN.md).

## 1. The idea in one picture

```
 Java source ─┐
 compiled     ├─► discovery ─► syntax ─► source-spoon ─► paths ─► bytecode-sootup ─► sql ─► rules ─► bindings ─► lineage ─► scenarios ─► validation ─► docs
 classes      │        │             │               │              │        │         │           │          │            │          │
 SQL / XML ───┘        └─────────────┴───────────────┴──────────────┴────────┴─────────┴───────────┴──────────┴────────────┘          │
                                         all stages read and write ONE shared model                                                   ▼
                                                  analysis-model.json  ───────────────────────────────►  13 Markdown documents + index
```

- Twelve **stages** run in order. Each is an independent class (`AnalysisStage`) that reads the shared **model** and adds facts.
- Nothing is written to Markdown straight from a parser. Documents are generated **only from the model**, so they can be
  regenerated from the saved `analysis-model.json` without analysing the code again.
- Everything is deterministic: the same input gives byte-identical model and documents. No AI is used for extraction.

## 2. The model: facts with evidence

The model (module `model`) is a graph:

- **Entities** with stable IDs such as `method:com.sample.shipping.StorageService#handle(com.sample.shipping.Shipment)`:
  repository, module, file, package, class, method, invocation, variable, field, expression, branch, SQL statement, table,
  column, parameter, business rule, scenario, transformation (a trace), output file, output field.
- **Relationships** between them, typed: `CONTAINS`, `CALLS`, `EXECUTES`, `READS_COLUMN`, `UPDATES_TABLE`, `DERIVED_FROM`,
  `GUARDED_BY`, `PART_OF_SCENARIO`, `APPLIES_RULE`, `PRODUCES`, ...
- **Evidence** on every fact: file, line range, which stage produced it, and how certain (`EXTRACTED`).
- **Trace status** on every relationship: `VERIFIED`, `PARTIAL` or `UNRESOLVED`.

Three rules keep the model honest:

1. **Repeating a link never makes it stronger.** If two stages report the same edge, the weaker status wins and the evidence is
   kept from both.
2. **A chain is VERIFIED only if every link is.** One PARTIAL link makes the whole trace PARTIAL; if no link is verified it is
   UNRESOLVED.
3. **Conflicts and failures are recorded, not hidden.** A stage failure becomes an ERROR warning in the model; a conflicting
   attribute keeps the first value and raises a warning.

## 3. The stages

### 1. `discovery`: what is there
Walks the source roots and inventories repository, modules (Maven `pom.xml` coordinates, with `groupId` and `version` inherited from the parent and `${project.version}` resolved, and declared dependencies; Gradle scripts
are listed only), and every file by kind (Java, SQL, XML, properties, ...). Skips `target`, `build`, `.git` and your `--exclude`
globs; marks test sources.
It then links the analysed modules (`MODULE DEPENDS_ON MODULE`, from the declared dependencies) and warns about a declared dependency that has the same groupId as an
analysed module but was not analysed, because a call into a module that is not in the analysis is external and every trace through it is PARTIAL. Two roots whose folders share a name
(`common/src`, `module-1/src`) get distinct repository names so evidence paths keep their module. The documents show the module of each class, rule and method when more than one module is analysed.

### 1b. `syntax`: what the source contains, counted independently
A parse-only census with the JDK's own parser (the javac Compiler Tree API): no classpath, no dependencies, and independent of Spoon. Every method,
constructor, initialiser, field initialiser and lambda is a unit; every statement and every **decision point** is counted, with its outcomes:

| Decision | Outcomes |
|---|---|
| `if`, `?:` | then, else (an implicit else counts) |
| loop | enter, exit |
| `switch` | one per case label, plus an implicit default for a statement without one; a switch expression has no implicit default |
| `when` guard | guard true, guard false |
| `try` | normal, plus one per `catch` |
| `&&`, `||` | evaluate right, short-circuit (each operator) |
| `assert`, boolean-bodied lambda | holds/fails, true/false |

It also keeps a **line ledger**: every code line must fall inside some record, so a construct the extractor does not know about shows up as an
unaccounted line (V015). This is the denominator the rest of the engine is measured against, because coverage that divides by what the
engine already found cannot see what it missed. A file that does not parse is reported (never skipped silently) and the other files still run.

**The cross-check (`validation`).** Each source outcome is matched against the conditional rules: an outcome is *captured* when a rule sits under
that branch arm (as the innermost guard or an outer one). The result is in `13_Analysis_Coverage_Report.md` (Completeness), in the console summary,
and as V014 findings. Measured with the rules built from paths (step 4): `sample-app` has **30** outcomes, **12 captured (40%)**; the remaining ones are loops, `try` blocks and the throwing `default`, which have no value-changing effect to attach a rule to. Before step 4 (arm-based rules) it was 59 outcomes with **12 captured (20%)** on the probe and 30 with **11 (37%)** on `sample-app`; the old list read: the four implicit `else` paths (including ArchiveService's REJECTED), the throwing `default`, all 10 loop outcomes
and the 4 `try` blocks are not rules yet. The number is a **ratchet** (`ConformanceTest`): it may only go up. The probe (`conformance/`) has about 35
constructs and a hand-counted golden file; the census must match it exactly.

### 2b. `paths`: every way through a method
Built on Spoon (it has the types and the calls), checked against the syntax census. For each method it builds a tree of **effects** (assignments, increments,
calls), **exits** (`return`, `throw`, `break`, `continue`, labeled ones included) and **decisions** (`if` with its implicit else, old and arrow `switch` with
fall-through and an implicit default, `for`/`foreach`/`while`/`do`, `try`/`catch`/`finally`, `assert`, and the ternary and `&&`/`||` inside expressions), then
flattens the tree into **paths**: the conditions that select a path, what it does in order, how it ends.

```
earlyReturn(int x):   if (x < 0) { return -1; }  x = x + 5;  return x;
   path 1   when  x < 0            then  -                 ends  return -1
   path 2   when  not (x < 0)      then  x = x + 5         ends  return x
```

This is what makes guard clauses visible: the rest of the method sits under the negated condition instead of looking unconditional. Fall-through (`case 2: case 3:`)
shares the next body, a `break` leaves the loop or switch it targets, a `finally` adds its effects to every way out of its `try`. Loops are abstracted to zero passes or at
least one pass. The number of paths can grow like 2^n, so flattening stops at a cap (default 128 per method) and the method is marked `pathsCapped`; the decisions
are never capped. Document 14 lists the paths of every method that branches.

**The identity check.** Every outcome the census counted must be in the path model (paired by file, line and kind, in source order). A missing one is a V016
finding: a WARNING when it is a defect, INFO when it is a kind the census counts that the path model does not know yet. The first run of this check found a
real disagreement: for a labeled loop Spoon starts the statement at the label and the JDK parser at the keyword, so the two tools put the loop on different lines.
Measured today: the probe has **59 of 59** outcomes in the path model (it was 48 before step 3, and the 11 it lacked were the switch expressions, pattern guards and lambda predicates), the step-3 fixture (`conformance/probe2`: lambdas, initialisers, switch expressions as a value and as an argument, a pattern switch with a guard) has **26 of 26**, and `sample-app` has **30 of 30**. The path tables are checked against hand-derived golden files (`conformance/golden/probe-paths.txt`, `step3-paths.txt`) and the flattener is
tested on hand-built trees. **Step 3 adds:** switch expressions (a value in a `return`, declaration or assignment gives one path per case and a `yield` ends its case; as a call argument or operand it is counted but not split), pattern labels and `when` guards (a guard is part of its case condition and a decision of its own), lambdas (each is a unit; a boolean-bodied one is a predicate decision), and field initialisers and static or instance initialiser blocks (units of their class). Not modelled yet (reported, never silent): local classes and other rare statements. Values are shown as written (`return x`), not yet substituted through the effects. Step 4 builds the rules and the lineage ordering from these paths.

### 2. `source-spoon`: what the code says
Parses every main-scope Java file with **Spoon** in no-classpath mode (no build needed; Java 21 syntax including records, sealed types, switch patterns and text blocks is tested). Extracts classes,
methods, fields, call sites, branches (`if`, `switch`, loops, `try`, `?:`) with nesting, and:

- **Calls.** `VERIFIED` when the call binds statically to one analysed method, `PARTIAL` when it may be overridden (interface,
  abstract, overridden) or leaves the analysed code. Every possible implementation in the sources is added as an extra PARTIAL edge.
- **Data flow** (`DataFlowExtractor`). Variables (parameters, locals), and **expressions**: every initialiser, assignment,
  compound assignment and return. `variable DERIVED_FROM expression` and `expression DERIVED_FROM operand` (variable, field,
  or the call whose result is used). An expression inside an `if` arm is `GUARDED_BY` that branch, with the arm recorded.
- **Bindings between methods.** Each call or constructor argument becomes an expression; `parameter DERIVED_FROM argument` links
  it to every callee the call may reach; `call DERIVED_FROM return expression` links a call to what the callee returns.
- **Concatenation parts.** A string built with `+` is kept as ordered parts. That is how a fixed-width line is recognised later.

### 3. `bytecode-sootup`: a second opinion on the call graph (optional)
With `--bytecode`, **SootUp** builds a class-hierarchy (CHA) call graph from the compiled classes. Edges use the same method IDs as
Spoon, so an edge seen by both becomes one edge with evidence from both. It reports a source-versus-bytecode cross-check and marks
each method reachable from an entry point as `verified`, `possible` or `no`. CHA over-approximates virtual calls, so reachability is
"possible", never a runtime guarantee.

### 4. `sql`: tables and columns
Finds SQL in Java string constants and concatenations, MyBatis mapper XML, iBatis sqlMap, other XML, `.properties` and `.yml` that hold SQL (a Java string literal equal to the key links the statement to the method that runs it), and `.sql` files, reads the schema from `CREATE TABLE` and
from `@Entity`/`@Table`/`@Column`, and parses each statement with **JSqlParser**.

- Records which table is read, inserted into, updated or deleted, and which columns, with the **role** (select list, where, set, join...).
- A column belongs to a table only if the SQL qualifies it, the schema decides it, or it is the only possible owner. Otherwise it is
  `(unresolved)`. Column names are **never inferred from Java variable names**.
- Parameters: each `?` is tied to the column it is compared with or assigned to and, for JDBC statements in local variables, to the Java
  expression bound with `ps.setXxx(n, expr)`. Each select item is tied to the Java code that reads it (`rs.getXxx("col")`) and to where
  the value goes next (`flowsTo`).
- Dynamic SQL is flagged and everything derived from it is PARTIAL. JPQL/HQL is reported, never analysed as SQL.

### 5. `rules`: conditions and calculations
From branches and expressions (model only, no parser). Two kinds:

- **conditional**: one rule per `if`/`else`/`switch` case/ternary arm that changes a value. It records the whole chain of enclosing
  conditions (outermost first; `else` reads `not (...)`), the effects (assignments, returns), constants used (static final fields with
  their values) and what the condition reads.
- **computation**: an unconditional definition built from operators, a named constant or a literal.

When the path stage ran, conditional rules are built from the **paths** rather than the arms: every value-changing effect is looked up on all paths of its unit; on all of them it is a computation, on some of them it is conditional on those paths' conditions (shared conditions, then `(a) OR (b)`). Effects on exactly the same paths share a rule, so a statement after an early return sits under the negated guard instead of looking unconditional. Lineage uses the same paths for ordering (`precededBy` on each expression): a variable read takes only the definitions that may have run before it, and an argument takes the rules of the statement it is part of.

A rule is **VERIFIED** when everything it reads is traced; a call outside the analysed sources makes it PARTIAL, unless the callee is on
the **pure-library list** (`java.math`, `String`, `Math`, boxed types, `java.time`): those only transform their inputs and hide no data
source. JDBC, `System`, HTTP clients and the like stay PARTIAL. The engine never names or explains a rule.

### 6. `bindings`: SQL results into Java parameters
A SQL result that the code passes straight into a call or constructor argument (`new Shipment(rs.getBigDecimal("quantity"), ...)`) is
linked to the callee's parameter. This is the join between the database world and the Java world.

### 7. `lineage`: the core
**Finds outputs.** A method that returns a concatenation of parts (at least two with a width) and is called by a method that also calls a
file sink (`Files.write`, `*Writer.write`, `print*`) is an output layout. Each part is an output field, named after the field its getter
returns. Start and end columns are the running sum of widths; a width is the first integer literal argument of the part's call
(`padLeft(x, 10, '0')`). That is a heuristic and the model says so (`widthSource`).

**Traces each field backwards** over `DERIVED_FROM` edges, depth-first, with a cycle guard and caps (`maxDepth`, `maxPathsPerField`; hitting
a cap is a warning). A path ends at a **terminal**:

| Terminal | Status | Meaning |
|---|---|---|
| `column` | VERIFIED | a table column: the goal |
| `constant` | VERIFIED | a literal or a `static final` constant |
| `external call`, `database read`, `field initialiser` | PARTIAL | a library call or initialiser outside the analysed code |
| `runtime input`, `untraced`, `entry input` | UNRESOLVED | `System.getProperty`/`getenv`, Scanner/Reader input, a value with no definition |

Four details make the traces precise, not merely plausible:

- **Call context.** After entering a method through a call, a parameter takes the argument of *that* call, not of every call site. Without
  this, a shared helper like `padLeft` would mix all fields together (an early version did exactly that; `shipmentId` traced to
  `quantity`). The context is dropped at a field, because a field's value is set elsewhere.
- **Receivers are not sources.** In `shipment.getQuantity()`, `shipment` is the object the call is made on, not a value that flows into the result.
  The value comes from the getter's return and the field behind it.
- **No double counting.** An operand already reached through an analysed call's own argument bindings is not followed a second time.
- **Layout constants** (widths, pad characters) describe the layout, not the data, so they are not reported as sources.

Each distinct result becomes a `TRANSFORMATION` entity: scenario, terminal, rules applied, status, and the full step list with file and line.
A field is VERIFIED only if **every** trace is.

### 8. `scenarios`: which flow is which
A scenario is **discovered, not configured**. A conditional rule guarded by a `switch` whose effect calls a method on a field initialised with
`new X(...)` makes `X` a handler class and the case label (`"DISPATCH"`) its trigger. It is VERIFIED when that field is `final` and never
reassigned. A scenario collects its methods (the handler's and everything they call inside the sources), rules, SQL, columns read and output
fields, finds the entry points that reach the dispatcher, and marks output fields whose derivation **differs** between scenarios. Traces
for different scenarios are never merged.

### 9. `validation`: does the model contradict itself?
Checks such as: every output field has a status; every rule and every database/call claim has evidence; no VERIFIED result sits on a
weaker link; every trace has a scenario; the same rule text in several methods is highlighted; dynamic and unsupported SQL is flagged; and
every evidence location still points at a real file and a line inside it. Findings are stored as warnings. `--strict` turns ERRORs into
exit code 3. A separate coverage report states every percentage with its denominator.

### 10. `docs`: Markdown from the model
Fifteen files (index plus 14 documents) with tables and Mermaid diagrams. Every statement carries `file:lines`. The generator is a pure function of
the model, so output is deterministic and `docs --model-dir` can recreate it.

## 3b. The functional documents (Phase 11)

Documents `F01` to `F06` restate the model for business readers. `FunctionalDocs` builds them from fixed templates over the extracted facts:
the application story (F01), the flow as a diagram and numbered steps (F02), for each scenario a decision table and diagram taken from the
paths of its methods plus its calculations (F03), a data dictionary with the source and conditions of every output field (F04), a glossary (F05)
and the questions only a person can answer (F06). `Humanizer` parses each Java expression and writes English: `signum() == 0` becomes "is zero",
`min` becomes "the smaller of", `divide(x, 2, HALF_UP)` becomes "÷ x, to 2 decimal places, rounding half up", and `not (...)` is turned into
the opposite wording. It never invents meaning: it spells names out, applies `glossary.properties`, and shows code it cannot parse as code.
Rules inside the output writer (text layout, padding) are left out of the business view.

**Completeness gates.** `--strict` also fails on V015, V016 and V017. V017 comes from `BranchCensus` in the `bytecode-sootup` stage: SootUp counts the
conditional jumps and switches in the compiled classes, and validation raises V017 when a class branches in bytecode but the source analysis found
no decision in it. It compares existence per class, not exact numbers, because one source decision can compile to several jumps.

## 4. A worked example: where does `quantity` in the manifest file come from?

`quantity` is columns 19-33 of the fixed-width line. The tracer's real answer for the storage scenario (shortened from
`08_Source_To_Output_Traceability.md`; every step has its file and line):

```
 1  part      amount(r.getQuantity(), 15)                       ManifestWriter.java:19     the output field
 2  call      amount(...)                                      -> into the helper that formats money
 3  return    padLeft(Long.toString(cents), width, '0')        ManifestWriter.java:34
 5  return    sb.append(s).toString()                          the padding helper
 6  param     s  <-  argument Long.toString(cents)             (only THIS call's argument: call context)
 8  local     cents  =  value.movePointRight(2).longValue()    pure library: a transformation (rule BR-0007)
10  param     value  <-  argument r.getQuantity()               ManifestWriter.java:19
12  call      r.getQuantity()                                   -> the getter, not the object r
13  return    quantity                                          ManifestRecord.java:23
14  field     ManifestRecord.quantity                          a field: the call context is dropped here
15  assign    quantity = quantity          (constructor)         ManifestRecord.java:16
17  argument  newQuantity                                       StorageService.java:25  (new ManifestRecord(...))
19  init      newQuantity = shipment.getQuantity().add(surcharge)  StorageService.java:23
21  return    quantity                                          Shipment.java:23
22  field     Shipment.quantity
24  param     quantity                                          Shipment.java:13  (constructor parameter)
25  sql result  quantity                                        ShipmentRepository.java:13  rs.getBigDecimal("quantity")
                                                               └─► column shipment.quantity   (schema.sql:1-7)   VERIFIED
```

Step 19 also reads `surcharge`, which is computed from `quantity` and `rate_pct`, so the storage scenario has further traces (with rule BR-0003) ending
at `shipment.quantity` and `shipment.rate_pct`, passing through the overdue rules (`isBefore(today)`, then `quantity > LARGE_QUANTITY = 1000`). `adjustment` is different: in the storage scenario it
ends at `System.getProperty("rate.adjustment." + shipmentId, "0.00")`, which is **UNRESOLVED**, while the shipped and archive scenarios give the constant
`BigDecimal.ZERO`. So `adjustment` is PARTIAL, and `12_Open_Questions_And_Gaps.md` names the exact missing link.

## 5. Design principles

- **Evidence or silence.** No fact without a file and line. No business meaning is invented; the engine does not name or explain rules.
  Explanations belong to a person or an AI that must cite this evidence and label its wording `AI_INTERPRETED`.
- **Approximations are labelled.** PARTIAL and UNRESOLVED are first-class results, not failures. Nothing approximate is ever shown as VERIFIED.
- **Independent, testable stages.** Each module has its own tests. Later stages read only the model, so they run without any parser.
- **Reproducible.** No timestamps, no host paths, stable ordering: identical input gives identical output.
- **Never executes your code.** Analysis is static.

## 6. Extending it

| To add | Do this |
|---|---|
| A new analysis | Implement `AnalysisStage` (one method, `run(StageContext)`), read/write the `AnalysisModel`, add it to `Engine.stages()` |
| A new document | Add a builder to `DocsGenerator` (it receives a `DocContext` over the model) |
| Another output kind (CSV, JSON, DB write) | Extend output discovery in `LineageExtractor`: produce `OUTPUT_FIELD`s with a root expression; the tracer, docs and validation then work unchanged |
| Treat another library as a pure transformation | Add its package prefix to `PureLibraries` (shared by `rules` and `lineage`), or pass your own list to `RulesStage`/`LineageOptions` |

## 7. Limits (all reported in `12_Open_Questions_And_Gaps.md`)

- **Completeness: the path model holds every outcome; rules and lineage now follow it (step 4).** The syntax census counts every decision outcome; the path model holds **all of them** on the probe (59 of 59), on the step-3 fixture (26 of 26) and on `sample-app` (30 of 30), including implicit else paths, loops, `try`/`catch`, throwing defaults, switch expressions, pattern guards, lambdas and initialisers (document 14). Rules and lineage are built from them when the path stage ran, so documents 04, 07, 08 and 11 show ArchiveService's ARCHIVED and REJECTED under their own conditions. Still open: a ternary used as a returned or assigned value stays expression-level (its own arm rules apply), a switch expression used as a call argument is counted but not split, units with more than 150 expressions get no ordering, and loops, `try` blocks and the throwing default have no rule of their own.
- **Static only.** Reflection, dependency injection, callbacks and generated code are not resolved.
- **Call graph.** Class-hierarchy based: virtual and interface calls list every compatible target, so they are PARTIAL.
- **Field-based data flow.** All instances of a class share their field's sources; definitions are not ordered by control flow, so a value can
  appear to depend on a later reassignment (the shipped `status` shows the fee columns for this reason). Values stored in collections
  are not followed element by element. Varargs arguments are not bound.
- **Outputs.** Only fixed-width layouts built by string concatenation are detected. Database writes (`UPDATE ... SET`) are not yet traced as
  outputs, and an output file's path is not traced. Type, precision and padding of fields are not derived.
- **SQL.** Stored-procedure bodies, `MERGE`, window functions and ORM-generated queries are not analysed; Spring `JdbcTemplate` and
  statements passed between methods are recorded but their bindings are not derived. `ALTER TABLE` is ignored.
- **Not modelled yet.** Calls and branches in field initialisers and static blocks, `++`/`--` as definitions.
- **Scenarios.** Recognised from a `switch` over handler objects held in `final` fields; other dispatch styles are not recognised.

## 8. Where the code is

| Module | Responsibility |
|---|---|
| `model` | Entities, relationships, evidence, trace status, `ModelIndex` |
| `core` | Config, JSON store, `Pipeline`, `PureLibraries`, Mermaid call graph |
| `discovery` | Phase 1 inventory |
| `syntax` | Parse-only census with the JDK parser: units, statements, decision outcomes, line ledger |
| `paths` | Path model on Spoon: effects, exits, decisions, paths, identity with the census |
| `source-spoon` | Spoon extraction: types, calls, branches, data flow, bindings |
| `bytecode-sootup` | SootUp call graph and reachability |
| `sql` | SQL discovery, parsing, schema, parameter mapping |
| `rules` | Business rules |
| `lineage` | `BindingsStage`, output discovery, the tracer, `LineageQuery` |
| `scenarios` | Scenario discovery |
| `validation` | Integrity checks and coverage metrics |
| `docs` | The 14 documents and index |
| `cli` | The command line and the runnable jar |
| `conformance` (not a module) | The probe (about 35 constructs) and its hand-counted golden numbers |
| `sample-app` | The fixture used by the tests (also an ordinary Maven module) |
