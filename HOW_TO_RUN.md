# How to run it

The engine is one runnable jar. You give it Java source (and optionally compiled classes); it writes a saved model
(`analysis-model.json`) and 14 Markdown documents that explain the code and trace every output field back to its source.
Java only: no Python, no Docker, no database, no network at run time, and it never executes your code.

## 1. What you need

| Need | Version |
|---|---|
| JDK | 21 or later (developed and tested on JDK 25). A JDK, not only a JRE: the syntax census uses the JDK's own parser at run time |
| Maven | 3.9 or later (only to build the jar) |

## 2. Build the jar (once)

```powershell
mvn clean package
```

This runs all tests (about 260) and produces a single standalone jar:

```
cli/target/code-intelligence-engine.jar
```

Add `-DskipTests` to build faster. After that you only need Java to run it; copy the jar anywhere.

## 3. Try it on the sample application

The repository contains a small shipping batch (`sample-app`: three scenarios, SELECT and UPDATE SQL, a fixed-width
manifest file). From the repository root:

```powershell
java -jar cli/target/code-intelligence-engine.jar analyze `
  --source sample-app `
  --bytecode sample-app/target/classes `
  --output analysis-output
```

(bash: use `\` instead of the backtick.) It takes a few seconds and prints:

```
Stages
  discovery          ok          26 ms
  syntax             ok         410 ms
  source-spoon       ok        1375 ms
  paths              ok          60 ms
  bytecode-sootup    ok         271 ms
  sql                ok         150 ms
  rules              ok          20 ms
  bindings           ok           2 ms
  lineage            ok          47 ms
  scenarios          ok          25 ms
  validation         ok          16 ms
  docs               ok         110 ms

Found: 12 classes, 40 methods, 2 SQL statements, 17 rules, 3 scenarios, 5 output fields
Output fields: 4 VERIFIED, 1 PARTIAL, 0 UNRESOLVED (of 5)
Decision outcomes in the source: 30, captured as conditional rules: 11 (37%); see 13_Analysis_Coverage_Report.md
Decision outcomes in the path model: 30 of 30 (all of them)
Warnings: 0 error(s), 0 warning(s), 8 note(s)
```

Open `analysis-output/00_Index.md`. A finished copy of this output is in [`examples/sample-app-output/`](examples/sample-app-output)
so you can read the documents without running anything. `adjustment` is PARTIAL on purpose: in the storage scenario its value
comes from `System.getProperty`, which static analysis cannot know. That is what a partial trace should look like.

## 4. Analyse your own code

1. **Point `--source` at the Java source.** A repository root, a module, or a `src/main/java` folder all work. Repeat
   `--source` for several roots. Test sources (`src/test/`) are skipped. No classpath or build is needed.
2. **Optionally add `--bytecode`.** Compile your project (target Java 21 or lower) and pass the classes folder or a jar,
   for example `target/classes` or `build/classes/java/main`. This adds a second, bytecode-level call graph and
   "reachable from an entry point" information. Everything else (rules, SQL, lineage, scenarios) works without it.
3. **Run `analyze`:**

```powershell
java -jar code-intelligence-engine.jar analyze `
  --source C:\work\shipping\src\main\java `
  --source C:\work\shipping\src\main\resources `
  --bytecode C:\work\shipping\target\classes `
  --output C:\work\shipping-analysis
```

4. **Read the documents** in the output folder, starting with `00_Index.md`.

For a large codebase give the JVM more memory: `java -Xmx4g -jar ...`.

### Projects with several modules

If `module-1` uses a `common` module, give the engine **both**. All the Java under all roots is analysed as one model, so a call from `module-1` into `common` resolves, and a value that comes from `common` (a constant, a rate, a shared rule) is traced all the way, with evidence pointing into the `common` sources.

```powershell
java -jar code-intelligence-engine.jar analyze --source my-project --output out
```

```powershell
java -jar code-intelligence-engine.jar analyze --source my-project/common --source my-project/module-1 --output out
```

- **Easiest: the parent folder, once.** The modules are found from the `pom.xml` files. Repeating `--source` once per module folder gives the same analysis.
- **Pass the module folders, not their `src` folders.** If two roots have the same folder name (`common/src` and `module-1/src`), the engine now names them `common/src` and `module-1/src`, so every evidence path still says which module it is in. A module `groupId` or `version` inherited from the `<parent>` is filled in, and `${project.version}` in a dependency is resolved.
- **If a module is missing you are told.** When `module-1` declares a dependency on a module with the same `groupId` as the analysed code but you did not analyse it, discovery warns (it names the dependency and what to do), `01_Module_Overview.md` marks it in bold under "Project modules NOT analysed", and anything that passes through it is PARTIAL ("external call"). Third-party dependencies and test-scoped ones are not flagged.
- **The documents show the modules.** With more than one module, documents 02, 04 and 10 get a Module column, and `10_Class_Method_Dependencies.md` has a **Module dependencies** graph that sets what the build files declare against the calls the code really makes (a declaration with no call, or calls with no declaration, are both shown).
- **Compiled classes** (`--bytecode`) are given once per module classes folder, for example `--bytecode common/target/classes --bytecode module-1/target/classes`. Not covered by a test yet.
- **Limits.** Module links come from Maven `pom.xml` dependencies; Gradle builds are inventoried but their dependencies are not parsed. A dependency declared only in a parent pom's `dependencyManagement` (no version in the child) is not matched.

### Options of `analyze`

| Option | Meaning |
|---|---|
| `-s, --source <path>` | Directory or file to analyse. Repeat for several. Required unless `--config` gives it. |
| `-b, --bytecode <path>` | Compiled classes (directory or jar) for the call graph. Repeat for several. Optional. |
| `-o, --output <dir>` | Where to write the model and documents. Default `analysis-output`. |
| `--java-release <n>` | Java language level of the analysed code. Default 21. |
| `-x, --exclude <glob>` | Skip matching files, e.g. `**/generated/**`. Repeat for several. |
| `-c, --config <file>` | JSON file with the same settings (see below). Command line options override it. |
| `--continue-on-error` | Keep running later stages if one fails. Default: stop at the first failure. |
| `--strict` | Exit with code 3 if validation finds errors **or a completeness defect**: V015 (code the census cannot account for), V016 (a decision outcome missing from the path model), V017 (the compiler made branches in a class where the source analysis found no decision; needs `--bytecode`). Useful in CI. |
| `-q, --quiet` | Show only warnings and errors on stderr. |

### Config file

```json
{
  "sourceRoots": ["C:/work/shipping/src/main/java"],
  "outputDir": "C:/work/shipping-analysis",
  "javaRelease": 21,
  "excludeGlobs": ["**/generated/**"],
  "continueOnError": false,
  "bytecodeRoots": ["C:/work/shipping/target/classes"]
}
```

```powershell
java -jar code-intelligence-engine.jar analyze --config analysis.json
```

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | A stage failed. The model is still saved; its ERROR warnings say why. |
| 2 | Bad usage or input (a path does not exist, no source given, unknown option, unknown field name) |
| 3 | `--strict` and validation reported errors |

## 5. What you get

| File | Answers |
|---|---|
| `00_Index.md` | Start here: the list of documents and how to read them |
| `01_Module_Overview.md` | What was analysed: modules, build metadata, files, packages |
| `02_Functionality_Inventory.md` | Entry points, scenarios, inputs and outputs, classes, unreachable methods |
| `03_Execution_Flow.md` | Possible call flow from each entry point, as Mermaid diagrams |
| `04_Business_Rules.md` | Conditions and calculations found in the code (`BR-0001`...), each with evidence |
| `05_Database_Inventory.md` | Tables and columns and how each is read or written |
| `06_SQL_Analysis.md` | Every SQL statement, its tables, and its parameter and result bindings |
| `07_Field_Mapping.md` | For every output field: source table.column, SQL, methods, rules, condition, derivation, scenario, status |
| `08_Source_To_Output_Traceability.md` | Step-by-step reverse trace of every field, and a forward index (column to outputs) |
| `09_Output_File_Specification.md` | Layout of each output file: start, end, width |
| `10_Class_Method_Dependencies.md` | Inheritance, class-level call graph, per-method call counts |
| `11_Business_Scenario_Analysis.md` | Each scenario: trigger, methods, rules, columns read, outputs affected |
| `12_Open_Questions_And_Gaps.md` | Everything not established: missing links, rules to review, limits |
| `13_Analysis_Coverage_Report.md` | How much of what was found was analysed, with denominators, plus a completeness section: decision outcomes in the source versus those captured as rules |
| `14_Decision_Paths.md` | Every execution path of every method that branches: when it is taken, what it does in order, how it ends. Guard clauses and else paths are visible here. |
| `F01_Business_Overview.md` | **Functional.** What the application does in one page: scenarios, inputs, outputs. For business analysts and subject matter experts. |
| `F02_End_To_End_Business_Flow.md` | **Functional.** The flow from the start of the job to the result, as a diagram and numbered steps. |
| `F03_Business_Scenarios_And_Rules.md` | **Functional.** For each kind of work: when it applies, every way it can go (a decision table and a diagram), and its calculations. |
| `F04_Data_Dictionary.md` | **Functional.** Every output field: position, width and where its value comes from per kind of work, with the conditions. |
| `F05_Glossary.md` | **Functional.** Terms, columns and fixed values spelled out, with a column for the business definition. |
| `F06_Questions_For_Business_Experts.md` | **Functional.** What the code cannot say and a person must confirm. |
| `analysis-model.json` | The saved model. All documents are generated from it. |

Every statement carries `file:startLine-endLine`. Open the file at that line to check it.

**Trace status.** VERIFIED means every link in the chain was verified. PARTIAL means at least one link is an approximation (for
example a call to a library the engine does not treat as a pure transformation). UNRESOLVED means the value cannot be traced
statically (for example it is read from a system property at run time). Neither PARTIAL nor UNRESOLVED is an error: they mark
exactly where a person should look.

## 6. Work from the saved model (no re-analysis)

The model is saved, so you can ask questions and regenerate documents without parsing the code again.

```powershell
# regenerate the documents (byte-identical for the same model)
java -jar code-intelligence-engine.jar docs --model-dir analysis-output

# how is an output field derived?  (--steps prints every step with file:line)
java -jar code-intelligence-engine.jar trace --model-dir analysis-output --field adjustment --steps

# which output fields does a column feed?  (forward tracing / impact)
java -jar code-intelligence-engine.jar trace --model-dir analysis-output --column shipment.quantity

# re-run the integrity checks; --source also checks that evidence still matches the files on disk
java -jar code-intelligence-engine.jar validate --model-dir analysis-output --source sample-app
```

Example:

```
> trace --field adjustment
Output field adjustment  (positions 44-53)  trace status PARTIAL
Gaps: StorageService: runtime input java.lang.System.getProperty("rate.adjustment." + shipmentId, "0.00") [UNRESOLVED]
  [UNRESOLVED] StorageService -> runtime input java.lang.System.getProperty("rate.adjustment." + shipmentId, "0.00")
        rules: calc ManifestWriter.amount
  [VERIFIED] StorageService -> column shipment.id
        rules: calc ManifestWriter.amount
  [VERIFIED] ArchiveService -> constant java.math.BigDecimal.ZERO
  [VERIFIED] DispatchService -> constant java.math.BigDecimal.ZERO
  ...

> trace --column shipment.quantity
Column shipment.quantity feeds:
  quantity      [VERIFIED] StorageService  (2 paths)
  quantity      [VERIFIED] ArchiveService
  quantity      [VERIFIED] DispatchService  (3 paths)
  fee          [VERIFIED] StorageService
  fee          [VERIFIED] DispatchService
  status       [VERIFIED] DispatchService  (3 paths)
```

`shipmentId` does not appear for `shipment.quantity`: it depends on the `id` column only.

## 7. Use the results with GitHub Copilot

The engine writes deterministic evidence; an AI only explains it. `.github/prompts` and `.github/agents` contain ready prompts
(`explain-output-field`, `explain-business-rule`, `review-gaps`) that read `analysis-model.json` and the documents and must
quote the evidence and label their wording AI_INTERPRETED.

## 8. Use it from Java

```java
AnalysisConfig config = new AnalysisConfig(List.of("src/main/java"), "analysis-output", 21, List.of(), false);
Engine.Run run = Engine.analyse(config);                       // com.chiru.engine.cli.Engine
AnalysisModel model = run.model();
LineageQuery.reverse(model, "quantity");                         // com.chiru.engine.lineage.LineageQuery
```

Every stage is an `AnalysisStage` and can be run on its own with a `Pipeline`; the modules are independent (see
[HOW_IT_WORKS.md](HOW_IT_WORKS.md)).

## 9. Run the tests

```powershell
mvn test                          # everything, including the end-to-end test of the real command line
mvn -pl cli -am test              # only the end-to-end test (and what it needs)
```

The end-to-end test runs the CLI on `sample-app` and checks the stages, all 15 files (index and 14 documents), that every `file:line` reference
in them points at a real line, regeneration from the saved model, determinism, `trace`, `validate`, config files and bad input.

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `bytecode-sootup` is "skipped" in the notes | No `--bytecode` was given. Fine: only reachability is lost. |
| SootUp reports unsupported class versions | Compile the classes for Java 21 or lower (`--release 21`) before passing `--bytecode`. |
| "no fixed-width output layout ... was found" | Output detection currently finds fixed-width layouts built by string concatenation and written with a file writer. Other outputs (CSV, JSON, DB writes) are not traced as outputs yet. Rules, SQL, scenarios and call flow are still produced. |
| A field is UNRESOLVED or PARTIAL | See the "Missing link" column in `12_Open_Questions_And_Gaps.md`. It names the exact call or value. |
| `Unresolved columns` in `05_Database_Inventory.md` | The SQL used a column without a table qualifier and the schema could not decide. Add the schema (`CREATE TABLE` in a `.sql` file or `@Entity`/`@Column` classes) to the source roots. |
| Many `PARTIAL` call edges | Expected. Interface and overridable calls list every compatible implementation (class-hierarchy analysis). |
| Out of memory | Run with `java -Xmx4g -jar ...` and exclude generated code with `--exclude`. |
| "Decision outcomes in the source ... captured as conditional rules" is low | Expected: only outcomes with a value-changing effect become rules (loops, `try` blocks and a throwing default do not). **Use `14_Decision_Paths.md` for the complete list of conditions**: the path model holds every outcome of the supported kinds (the "in the path model" lines): implicit else paths, loops, try/catch, switch expressions, pattern guards, lambdas and initialisers. V014 lists what the rules miss, V016 what the path model does not hold. The path model supports every kind the census counts, so a V016 WARNING is a defect; please report it. Documents 04, 07, 08 and 11 are built from the paths, so a guarded statement shows its condition. |
| `syntax` stage warns "skipped: no Java compiler" | You are running on a JRE. Use a JDK; the rest of the analysis still runs without the census, but the completeness numbers are missing. |
| `V012` warnings (evidence file not found) | Run `validate` with the same `--source` the model was built from, or the sources moved. |

## Functional documents for business readers (F01 to F06)

Documents `F01` to `F06` are written for business analysts and subject matter experts. Start with `F01_Business_Overview.md`, then
`F02_End_To_End_Business_Flow.md` for the whole flow, `F03` for the rules of each kind of work and `F04` for the meaning and source
of every output field. `F06` lists what only a person can answer.

They are generated from the same model as the technical documents, by fixed rules (no AI). Conditions and calculations are rewritten
as English (`shipment.getQuantity().signum() == 0` reads "shipment quantity is zero"); anything too complex to rewrite safely is shown as code
and counted in `F06`. Names are only spelled out from the code, so the business meaning has to be added by you:

1. Create `glossary.properties` in the output directory (next to the documents), one `name = business term` per line. A name is a variable or
   method property (`quantity`, `ratePct`) or a `table.column` (`shipment.rate_pct`):

   ```properties
   quantity = outstanding quantity
   ratePct = annual surcharge rate
   shipment.rate_pct = annual surcharge rate
   ```

2. Regenerate without analysing again: `java -jar code-intelligence-engine.jar docs --model-dir analysis-output`

The new terms are used in every functional document, and `F06` stops asking for a glossary.

## The pipeline: one command, with an AI hand-off and a list of what is missing

Build once, then everything is in `dist/`:

```powershell
.\build-dist.ps1            # builds, then collects the jars and prompts into dist/
.\dist\run-pipeline.ps1 -Source my-project/src -Bytecode my-project/target/classes -Output out
```

`dist/` holds `code-intelligence-engine.jar` (the one jar to run; every module is inside it), `lib/` (the module jars, for reference),
`prompts/` (the AI driver) and `run-pipeline.ps1`. A plain command-line jar is enough; Spring Boot would add a web server nobody needs here.

`pipeline` runs: analysis (all stages) → functional documents F01–F06 using `context/` → the AI hand-off (`ai/PROMPT.md`, one small
`ai/pack/<section>.json` per section) → a check of any replies in `ai/responses/` → `NEEDS_INPUT.md` and `PIPELINE_STATUS.md`. Run it again after
every change: nothing is lost, files you wrote (`context/answers.properties`, `context/glossary.properties`, `ai/responses/*`) are never overwritten.

**Where the AI fits.** Give the AI (GitHub Copilot, Claude, any chat or agent) `ai/PROMPT.md` and one pack at a time; it writes
`ai/responses/<same name>.json`. Each paragraph must cite fact ids; the pipeline drops any paragraph whose numbers, quoted values or labels are not
in the facts it cites, that guesses, or that contains code. Accepted paragraphs go to `N01_AI_Narrative.md` (marked AI-worded); facts the AI did not
use are listed unchanged. The check proves every number, value and label is traceable to a fact; it cannot prove a sentence is a faithful paraphrase,
so F01–F06 stay the reference and a person should read N01.

**Run it hands-free.** `prompts/functional-docs.prompt.md` is a GitHub Copilot prompt file (copy it to `.github/prompts/`, then run `/functional-docs`
in agent mode); `prompts/skill/SKILL.md` is the same as a Claude skill. It runs the pipeline, writes the replies pack by pack, reads the verification
report, asks you each question in `NEEDS_INPUT.md`, writes your answers into `context/` and reruns until nothing is missing.

**Minimal context, maximum accuracy.** The AI never sees the source code, only a pack of at most a few dozen verified facts for one section, with
the rules. The business supplies what the code cannot: `context/answers.properties` (`system.purpose`, `source.<field>`, `number.<rule>`, ...) and
`context/glossary.properties` (names and constants). Exit code 4 with `--require-complete` while anything is missing.
