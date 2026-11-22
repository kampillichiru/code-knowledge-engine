# Java Code Intelligence Engine

Pure-Java (Java 21, Maven) static analysis that documents an unknown Java codebase and traces every
output field back to its source, with file and line evidence.

## Status

| Phase | State |
|---|---|
| 1 Design | done |
| 2 Foundation (`model`, `core`) | done |
| 3 Discovery and Spoon (`discovery`, `source-spoon`, `sample-app`) | done |
| 4 SootUp call graph (`bytecode-sootup`) | done |
| 5 SQL analysis (`sql`) | done |
| 6a Data flow (in `source-spoon`) | done |
| 6b Business rules (`rules`) | done |
| 7a Interprocedural bindings (`source-spoon`, `lineage`) | done |
| 7b Lineage tracer and output discovery (`lineage`) | done |
| 8 Scenario analysis (`scenarios`) | done |
| 9 Documentation (`docs`): 14 documents plus index, from the model only | done |
| 10 Validation and hardening (`validation`, `cli`, end-to-end test) | done |
| 10b Completeness: step 1, syntax census, conformance probe, outcome cross-check (`syntax`, `conformance`) | done. Completeness is now measured: conditional rules capture 12 of 59 (probe) and 11 of 30 (sample-app) |
| 10b Completeness: step 2, path model (`paths`) | done. 48 of 59 on the probe, 30 of 30 on sample-app |
| 10b Completeness: step 3, switch expressions with yield, pattern guards, lambdas, initialisers | done. **The path model holds every outcome the census counts**: probe 59 of 59, step-3 fixture 26 of 26, sample-app 30 of 30. Next: step 4 (rules and lineage regenerated from paths), then the bytecode cross-check and gates |

**296 tests pass** on a clean build, including an end-to-end test that drives the real command line on `sample-app`.

## Use it

```powershell
mvn clean package
java -jar cli/target/code-intelligence-engine.jar analyze --source sample-app --bytecode sample-app/target/classes --output analysis-output
```

Then open `analysis-output/00_Index.md`. A finished copy is in [`examples/sample-app-output`](examples/sample-app-output). A two-module example (`module-1` uses `common`) is in [`examples/multimodule-output`](examples/multimodule-output).

- **[docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md)**: diagrams of the architecture, each module and stage, how linking and the AI step work, with a worked example.
- **[EXECUTION_GUIDE_MULTI_MODULE.md](EXECUTION_GUIDE_MULTI_MODULE.md)**: step by step for a new multi-module Maven project (which modules to include, the command, a loop for all modules, the checks, the AI step).
- **[HOW_TO_RUN.md](HOW_TO_RUN.md)**: build, run on your own code, all options, exit codes, config file, trace and regenerate commands, troubleshooting.
- **[HOW_IT_WORKS.md](HOW_IT_WORKS.md)**: the model, every stage, how a trace is built (with a real worked example), design principles, limits, how to extend.

Commands: `analyze` (run everything), `docs` (regenerate documents from the saved model), `trace` (`--field` reverse, `--column` forward), `validate`.

## Build

```powershell
mvn test                 # all tests
mvn clean package        # tests + the runnable jar cli/target/code-intelligence-engine.jar
```

Requires JDK 21+ (developed on JDK 25) and Maven 3.9+. Nothing else: no Python, no Docker, no network at run time.

## Modules

- `model`: versioned intermediate model: entities with stable IDs, typed relationships, `Evidence`
  (file, lines, extractor, certainty), `TraceStatus` (VERIFIED, PARTIAL, UNRESOLVED), warnings.
- `core`: `AnalysisConfig`, strict JSON `ModelStore`, `AnalysisStage` and fail-fast `Pipeline`.

- `discovery`: `DiscoveryStage` inventories roots into REPOSITORY, MODULE (Maven/Gradle build dirs) and FILE
  entities (JAVA, SQL, XML, PROPERTIES, YAML, JSON, BYTECODE, BUILD). Parses `pom.xml` coordinates and declared
  dependencies. Skips `target`, `build`, `.git` etc. and honours `excludeGlobs`. Gradle scripts are inventoried
  only (dependencies not parsed; reported as an INFO warning). Several modules: `groupId`/`version` inherited from `<parent>` and `${project.version}` are resolved;
  analysed modules are linked (`MODULE DEPENDS_ON MODULE`); a declared dependency with the same groupId as an analysed module that was not analysed is a WARNING (calls into it are
  external, traces through it PARTIAL); roots with the same folder name get distinct repository names (`common/src`, `module-1/src`). Documents 02, 04 and 10 show a Module column and
  document 10 a module dependency graph (declared versus called) when more than one module is analysed. Fixture: `conformance/multimodule`.
- `source-spoon`: `SpoonSourceStage` runs Spoon in no-classpath mode over main-scope Java files and extracts
  CLASS (kind, modifiers, annotations, stereotype, supertypes), METHOD (incl. constructors, entry points),
  FIELD (with initializer text), INVOCATION, and BRANCH (if, switch, loops, try, ternary) with nesting and the
  parent arm (`then`, `else`, `case ...`). All with file and line evidence.
  - Data flow (Phase 6a, `DataFlowExtractor`): VARIABLE (parameters, locals, catch variables) and EXPRESSION
    (local initialisers, assignments, compound assignments, returns) entities. `variable/field DERIVED_FROM
    expression` and `expression DERIVED_FROM operand` (variable, field including constants, or an invocation).
    `expression GUARDED_BY branch` with the arm in `parentArm`; `branch DERIVED_FROM operand` for what its
    condition reads. Expressions record `operators`, `literals` and `externalOperands` (e.g. `RoundingMode.HALF_UP`).
    An edge to an invocation is as strong as that call's weakest edge, so a value behind an external call is PARTIAL.
    Order-aware when the path stage ran: a variable read keeps only the definitions that may have run before it
    (`precededBy`, same unit); otherwise every definition is kept. `++`/`--`, field initialisers and static blocks are
    not modelled (reported as INFO warnings).
- `sample-app`: shipping batch fixture (12 classes, DISPATCH/STORAGE/ARCHIVE scenarios, SELECT and UPDATE SQL,
  fixed-width writer, one value that cannot be traced statically). Used by the tests; it is also an ordinary
  Maven module.

- `bytecode-sootup`: `SootUpCallGraphStage` builds a class-hierarchy (CHA) call graph with SootUp from the
  compiled classes in `bytecodeRoots` (directories or jars; classes must be a bytecode version the pinned
  SootUp/ASM reads, so compile targets at `--release 21` or lower). It writes `CALLS` edges using the same method
  IDs as Spoon, so an edge seen by both becomes one relationship with evidence from both. It then reports a
  source-versus-bytecode cross-check and marks each method `reachable` = `verified` (via VERIFIED edges only
  from an entry point), `possible` (only via PARTIAL edges, e.g. interface dispatch) or `no`.
- `sql`: `SqlStage` finds SQL in Java string constants and concatenations, MyBatis mapper XML, iBatis sqlMap, any other XML, `.properties` or `.yml` that holds SQL (linked to Java by the key a string literal uses, e.g. `getProperty("acct.select")`), and `.sql` files,
  reads schema from `CREATE TABLE` and from `@Entity`/`@Table`/`@Column` annotations, parses each statement with
  JSqlParser 5.4 and writes SQL_STATEMENT, TABLE, COLUMN and PARAMETER entities.
  - Table and column access is recorded as `READS_TABLE`, `INSERTS_INTO`, `UPDATES_TABLE`, `DELETES_FROM`,
    `READS_COLUMN`, `INSERTS_COLUMN`, `UPDATES_COLUMN`, each with the role (select list, where, join condition,
    group by, set clause, ...) in the evidence note.
  - A column is attributed to a table only when the SQL qualifies it, the schema decides it, or it is the only
    possible owner. Otherwise it becomes `column:(unresolved).<name>` with an UNRESOLVED edge and the reason.
  - Parameters: each `?` becomes an IN PARAMETER with the column it is compared with or assigned to. For JDBC
    statements held in local variables, `ps.setXxx(n, expr)` supplies `javaExpression` and `javaVariables`.
    Each select item becomes an OUT PARAMETER derived from its source columns; `rs.getXxx("col")` supplies
    `javaGetter`, `javaExpression` and `flowsTo` (the call or constructor argument the value goes to next).
  - `EXECUTES` edges link the executing method to the statement.
  - Dynamic SQL (non-constant parts become `__DYN_n__`; mapper `${}` and `<if>/<where>/<foreach>`) is flagged and
    everything derived from it is PARTIAL. JPQL/HQL (`createQuery`, `@Query` without `nativeQuery`) is reported and
    never analysed as SQL. Unparsable SQL with a known SQL consumer is a WARNING; SQL-like text with no consumer
    that does not parse is ignored and counted.
  - Stage order: discovery, source-spoon (for method links), then sql. It builds its own Spoon model.
  - Bindings (Phase 7a, also `DataFlowExtractor`): parameter variables carry a 1-based `index`. Each call or
    constructor argument becomes an EXPRESSION of kind `argument` (owned by the INVOCATION, with operands and
    guarding arm). `parameter DERIVED_FROM argument` is written for every analysed callee the call may reach,
    as strong as the call edge: VERIFIED when statically bound, PARTIAL for each possible implementer of a
    dynamic call. `invocation DERIVED_FROM return expression` links a call's result to what the callee returns.
- `lineage`: `BindingsStage` (Phase 7a, run after `source-spoon` and `sql`) links a SQL result value that is
  passed straight into a call or constructor argument to the callee's parameter:
  `parameter DERIVED_FROM OUT PARAMETER` (VERIFIED). The OUT parameter already points to its `table.column`, so a
  constructor field can be followed back to the column.
  - `LineageStage` (Phase 7b; run after `source-spoon`, `sql`, `rules` and `BindingsStage`) writes OUTPUT_FILE,
    OUTPUT_FIELD and TRANSFORMATION entities.
    - **Discovery**: a method returning a string concatenation of parts, at least two with a width, called by a method
      that also calls a file sink (`Files.write*`, `*Writer.write`, `print*`). Each part is a field, named after the
      field its getter returns; start/end are the running sum of widths. The width is the first integer literal
      argument of the part's call (a heuristic, stated in `widthSource`). On `sample-app` the positions match the
      `ManifestWriter` Javadoc (1-10, 11-18, 19-33, 34-43, 44-53). The output path itself is not traced.
    - **Trace**: depth-first walk back over `DERIVED_FROM`. A path ends at a table `column` (VERIFIED), a `constant`
      (VERIFIED), an `external call` or `database read` (PARTIAL), or a `runtime input` (`System.getProperty`, Scanner,
      readLine) or untraceable value (UNRESOLVED). Calls to the configured pure library list (`java.math`, `String`,
      `Math`, boxed types, `java.time` by default, `LineageOptions`) are transparent; any other library call stays PARTIAL.
    - **Context**: after entering a callee through a call, a parameter takes only that call's argument (so shared
      helpers such as `padLeft` do not mix fields). The call context is dropped at a field (instance state). The object
      a getter is called on is not a source of the value. Everything else is field-based.
    - **Result**: one TRANSFORMATION per distinct (scenario, terminal, rules, status) with `steps` (each with file and
      line), `rules` and `status`; `OUTPUT_FIELD DERIVED_FROM TRANSFORMATION`, `TRANSFORMATION DERIVED_FROM terminal`,
      `TRANSFORMATION APPLIES_RULE rule`. The scenario is the class implementing an analysed interface nearest the
      output on the path, else `(common)`. A field's `traceStatus` is VERIFIED only if every trace is; `gaps` names each
      PARTIAL or UNRESOLVED terminal; `constants` lists constant inputs. Caps (`maxDepth`, `maxPathsPerField`) are
      reported as warnings.
    - **Sample**: `shipmentId` reaches only column `id`; `quantity`, `fee` and `status` reach `quantity` and `rate_pct`
      through the storage, fee and cap rules; `adjustment` is UNRESOLVED for STORAGE (runtime property) and a constant
      for DISPATCH and ARCHIVE, so its status is PARTIAL.
- `rules`: `RulesStage` (Phase 6b) derives BUSINESS_RULE entities from the model only (run after `source-spoon`).
  - **conditional**: one rule per arm (then/else of an `if` or ternary, a `switch` case) that changes a value
    (assignment or return). Records `conditions` (whole enclosing chain, outermost first; else arms read
    `not (...)`), `effects`, `constants` (static final fields read, with initialiser), `reads`, `literals`.
    Loops and `try` are not conditions. Several effects in one arm form one rule.
    When the path stage ran, rules come from the paths instead of the arms (flow-sensitive): the condition is the
    conjunction of a path's conditions (a shared prefix plus `(a) OR (b)` when the effect is on several paths), and the rule
    carries `outcomes`. Methods without paths keep the arm-based rules.
  - **computation**: an unconditional definition built from operators, a named constant or (not a return) a
    literal. String concatenation does not count as an operator.
  - Edges: `method APPLIES_RULE rule`, `rule GUARDED_BY branch` (each condition in the chain), `rule DERIVED_FROM
    expression` (each effect). `traceStatus` is the chain status of those edges: PARTIAL when a condition or value
    goes through a call outside the analysed sources, except calls into the pure-library list (`PureLibraries`:
    `java.math`, `String`, `Math`, boxed types, `java.time`), which only transform their inputs. Rules are structure only, never named or explained here;
    naming is left to Copilot as `AI_INTERPRETED`.
  - Not rules: arms with no value-changing effect (throw, calls only), the implicit else of an `if` without else,
    switch-expression yields, ternaries outside an assignment or return (counted in an INFO warning).
- `syntax` (Phase 10b, step 1): `SyntaxStage` runs a parse-only census with the JDK parser (javac Compiler Tree API, no dependencies, independent of
  Spoon; adapted from the author's `JavaFacts`): SYNTAX_UNIT per method, constructor, initialiser or lambda, DECISION per decision point with its outcomes
  (if, ternary, loop, switch labels, when-guard, try/catch, `&&`/`||`, assert, boolean lambda), and a line ledger (V015). Needs a JDK at run time; on a JRE it
  warns and skips. `validation` compares the census with the conditional rules (`OutcomeCoverage`, V014, the Completeness section of document 13); the
  probe in `conformance/` has hand-counted golden numbers and `ConformanceTest` is a ratchet that may only go up. Rules now carry a `guards` attribute
  (every enclosing branch arm, innermost first).
- `paths` (Phase 10b, step 2): `PathStage` builds, for every method, lambda and initialiser, a tree of effects, exits and decisions from the Spoon AST (if with its implicit else, old and arrow switch
  with fall-through, all loops, try/catch/finally, assert, labeled break/continue, ternary and `&&`/`||` in expressions) and flattens it into PATH entities: the
  conditions that select a path, its ordered effects, how it ends (capped per method, reported when cut). PATH_DECISION entities are paired with the syntax census
  so every outcome in the source must be in the path model (V016; a labeled loop exposed a real Spoon-versus-javac disagreement on the first run). Probe: 59 of 59 (48 at step 2), `sample-app`: 30 of 30. Golden paths in `conformance/golden/probe-paths.txt`. Document 14 lists the paths.
- `paths` step 3: switch expressions (as a value: one path per case, `yield` ends its case; as an argument: counted, not split), pattern labels with `when` guards, lambdas (each its own unit, a boolean body is a predicate decision) and field or static/instance initialiser blocks (units of their class) are in the path model. Probe 59 of 59, `conformance/probe2` 26 of 26. Golden: `conformance/golden/step3-paths.txt`, `step3-outcomes.txt`.
- `scenarios` (Phase 8): `ScenarioStage` discovers scenarios from the code (a handler class selected by a dispatching `switch`,
  or named by the lineage traces) and attaches methods, rules, SQL, columns read and output fields (`PART_OF_SCENARIO`);
  marks output fields whose derivation differs by scenario. See HOW_IT_WORKS.md.
- `validation` (Phase 10): `ModelValidator` (checks V001-V013, findings grouped with examples), `Coverage` (metrics with explicit
  denominators), `ValidationStage` (stores findings as warnings).
- `docs` (Phase 9): `DocsGenerator` writes `00_Index.md`, the 13 documents of the master prompt and `14_Decision_Paths.md` from the model only
  (deterministic, so byte-identical on regeneration); `DocsStage`.
  Phase 11 adds the six functional documents `F01`..`F06` for business analysts and subject matter experts (`FunctionalDocs`, with
  `Humanizer`, a small expression parser that rewrites conditions and calculations as English). Business names come from an optional
  `glossary.properties` in the output directory.
- `bytecode-sootup` also runs `BranchCensus`: the number of branches the compiler produced per class, compared by validation (V017) with the
  decisions found in the source.
- `cli` (Phase 10): `Main` with `analyze`, `docs`, `trace`, `validate` (picocli 4.7.7); `Engine` wires the ten stages;
  maven-shade builds `code-intelligence-engine.jar`. Exit codes 0 ok, 1 stage failed, 2 bad input, 3 `--strict` validation errors.
- `core` also provides `PureLibraries`, the list of library types treated as pure transformations, shared by `rules` and `lineage`.
- `core` also provides `CallGraphMermaid`, which renders call edges from the model as a Mermaid flowchart
  (solid = verified, dashed = partial).

Run the bytecode stage by setting `bytecodeRoots` in the config (or `AnalysisConfig.withBytecodeRoots`). Without
it the stage is skipped with an INFO warning.

Sample result on `sample-app`: 141 call edges (42 VERIFIED, 99 PARTIAL, 0 UNRESOLVED); 97 are confirmed by both
analyses, 42 appear only in bytecode (compiler-generated: `super()` constructors, try-with-resources cleanup,
class initialisation) and 2 appear only in source (interface declarations, resolved to implementations by CHA).
Static initialisers (`<clinit>`), which the source stage does not model, are recovered from bytecode.

### Call edge semantics

| Status | Meaning |
|---|---|
| VERIFIED | call site binds statically to one analysed method (static, private, final, constructors, or no override found) |
| PARTIAL | target may be overridden (interface/abstract/overridden method) or lies outside the analysed sources. Possible implementers in the sources are added as extra PARTIAL edges |
| UNRESOLVED | Spoon could not identify the declaring type (missing classpath) |

### Known limits (Phases 3â€“5)

- SQL: parameter and result tracking covers JDBC `PreparedStatement`/`Statement` objects held in local variables
  with constant parameter indexes. Spring `JdbcTemplate`, JPA native queries, Spring Data `@Query` and statement
  objects passed between methods are recorded but their bindings are reported as "not derived".
- SQL: stored procedure and function bodies are not analysed; functions in expressions are listed but not
  distinguished from built-ins. `MERGE`, window functions and other nodes the analyser does not model are listed
  under `unsupported` and in a warning. `INSERT ... SELECT` maps columns by position only (no star).
- SQL: schema from DDL ignores `ALTER TABLE`; ORM table and column names taken from class and field names are
  marked as inferred (PARTIAL) because the naming strategy is unknown. MyBatis `resultMap` is not read.
- SQL: the same constant used by several methods yields one statement with one parameter set per executing method.

- Bytecode call graph is CHA: virtual and interface calls over-approximate (every compatible override is a
  possible target) and are never VERIFIED. Reflection, dependency injection, callbacks and invokedynamic
  bootstrap targets are not resolved. Only the configured bytecode is analysed; JDK and library methods appear
  as external, PARTIAL targets.
- Reachability treats PARTIAL edges as "possible", so it is an over-approximation, not a runtime guarantee.
- Control-flow branches and exception paths are taken from source (Spoon); SootUp is used for the call graph only.
- Bytecode-only edges are not yet classified as compiler-generated versus genuinely missing from source.

- Lineage (7b): only fixed-width layouts built by string concatenation are detected as outputs (no CSV, JSON or
  `String.format` yet, and no config override of the field list); database writes (`UPDATE ... SET`) are not traced as
  outputs yet; control dependence is only visible through the rules, and because definitions are not ordered a value can
  appear to depend on later reassignments (e.g. DISPATCH `status` shows the fee columns). The receiver rule skips the
  object a call is made on, so a variable used both as receiver and as a plain operand of one expression is traced
  only through the call. Layout widths and pad characters are skipped as sources when they would otherwise appear.
- Data flow is field-based and call-site-insensitive: a parameter or field is fed by every call site and every
  assignment, and definitions are not ordered by control flow (each keeps its guarding branch arm). Objects are
  not tracked per instance. Varargs arguments are not bound (INFO warning). Values stored in collections, and
  anything passed through reflection or dependency injection, are not followed.
- Calls and branches in field initialisers and static blocks are not modelled; the count is reported as a warning.
- Implementer lookup uses only analysed sources; Spring/DI wiring and reflection are not resolved.
- Spoon 11.5.0 parses Java 21 syntax (records, sealed types, switch patterns, text blocks) and accepts compliance
  level 25 on JDK 25. Java 25-only syntax (module imports, flexible constructor bodies) is not tested yet.

Key rules: repeating a link never strengthens its status; a chain is VERIFIED only if every link is;
conflicting attributes are kept (first wins) and warned about; stage failures are recorded, not hidden.

## Pinned versions

JUnit 6.1.3, Jackson 2.23.0, SLF4J 2.0.20, Logback 1.6.8, Spoon 11.5.0, SootUp 3.0.1, JSqlParser 5.4,
picocli 4.7.7, maven-shade-plugin 3.6.0. JGraphT 1.5.3 is pinned but not used: the tracer and call-graph walks
are small purpose-built traversals over the model. Spoon and SootUp are verified on JDK 25 by the tests.

## GitHub Copilot integration

No API is needed. The engine writes deterministic evidence; Copilot explains it.

- `.github/copilot-instructions.md`: project rules (evidence required, no invention).
- `.github/agents/`: `lineage-analyst`, `rule-reviewer`.
- `.github/prompts/`: `explain-output-field`, `explain-business-rule`, `review-gaps`.
- `.github/skills/evidence-based-explanation/`: reusable explanation discipline.

## Pipeline (end to end)

`java -jar code-intelligence-engine.jar pipeline --source <src> [--bytecode <classes>] --output <out>` runs the analysis, the functional
documents, the AI hand-off (`ai/PROMPT.md` and one pack per section), the check of the AI's replies (`DocsGenerator`/`Narration`: cited facts, numbers,
values and labels must all be in the facts; no guessing, no code) and the list of missing information (`NEEDS_INPUT.md`); answers go in
`context/answers.properties` and the run is repeated. `build-dist.ps1` collects the jars and the Copilot/Claude prompts into `dist/`.
`DocEvidenceStage` (`evidence`) attaches Javadoc and `//` comments to classes, methods and fields; they prefill the glossary for the business to confirm.

## Field reference and per-field AI step

- `F07_Field_Reference.md`: one section per output field, traced both ways. Formatting helpers such as `amount(value, 15)` are expanded into numbered steps with worked examples (illustrative values, computed from the code's own formatting steps) and a width check; fields whose result can be longer than the field are listed under "Width problems".
- `ai/pack/field-<name>.json`: one pack per field (source column, calculation steps with file:line, what changes it, formatting steps, examples, width check). Give them to an agent (for example Copilot in agent mode) with `ai/PROMPT.md`; verified replies in `ai/responses/` become `N02_Field_Derivations.md`.
- Limits: only the idioms `padLeft/padRight/leftPad/rightPad`, `String.valueOf/format`, `movePointRight/Left`, `setScale`, `longValue`, `trim`, `toUpperCase` and helpers built from them are expanded; anything else shows no steps (never a guess).
