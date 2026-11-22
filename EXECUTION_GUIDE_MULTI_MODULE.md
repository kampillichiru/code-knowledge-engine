# Execution guide: running on a new multi-module Maven project

Use this when you point the engine at a project such as: one parent POM, many modules (for example 28 modules that write TXT files,
1 that writes a DAT file) and one `common` module that the others use. You analyse **one writer module at a time, together with the
modules it depends on**, and get one set of documents per writer module.

Java only. No Docker, no database connection, no network at run time. The engine reads code; it never runs it.

---

## 0. One-time setup

| Need | Detail |
|---|---|
| JDK 21+ | A JDK, not a JRE. Check with `java -version`. The project you analyse may be older (Java 8, 11): see `--java-release` below. |
| The jar | `dist\code-intelligence-engine.jar`. Build it with `.\build-dist.ps1 -SkipTests` from the repository root (needs Maven 3.9+), or copy the finished `dist` folder. |
| Compiled classes (recommended) | In the target project run `mvn -q -DskipTests compile` once. This creates `target\classes` in every module. Optional, but it adds a second call graph and a reachability check. |

---

## 1. Decide what to analyse

For the module you want to document (call it `txn-report`):

1. List its dependencies on other modules in its `pom.xml` (look for `<dependency>` entries whose `groupId` is your own).
2. Always include `common`, and any other in-house module it uses.
3. Do **not** include the other writer modules. They are separate runs.

| Run | `--source` folders |
|---|---|
| Module A | `modA`, `common` |
| Module B | `modB`, `common` |
| The DAT module | `datmod`, `common` |

Why `common` is in every run: the SQL, the helper methods and the formatting code in it are part of how A's fields get their values.
Without it, fields would show as "comes from outside the analysed code".

---

## 2. Point at the module folder, not at `src\main\java`

Use the **module root** (the folder that holds `pom.xml`). Then `src\main\resources` is read too, which is where legacy projects keep
SQL in XML (iBatis `sqlMap`, MyBatis mappers), `.properties` and `.yml` files. Test sources (`src\test`) are skipped automatically.

---

## 3. The command

PowerShell, from any folder:

```powershell
$repo = 'C:\path\to\repo'          # the folder with the parent pom.xml
$mod  = 'txn-report'               # the module you want documented
$jar  = 'E:\Chiru\code-knowledge-engine\dist\code-intelligence-engine.jar'

java -jar $jar pipeline `
  --source "$repo\$mod" `
  --source "$repo\common" `
  --bytecode "$repo\$mod\target\classes" `
  --bytecode "$repo\common\target\classes" `
  --exclude "**/target/**" --exclude "**/src/test/**" `
  --output "C:\analysis\$mod"
```

Notes:

- `--source` and `--bytecode` can be repeated, once per module.
- Leave out the two `--bytecode` lines if you have not compiled. Everything else still works.
- Old project (Java 8 or 11)? Add `--java-release 8` (or 11) so the source parser uses the right language level.
- Give each module its own `--output` folder so runs never overwrite each other.
- Add `--require-complete` in a CI job: the exit code is 4 while questions for the business are still open.
- Add `-q` for less console output.

### Run all 30 modules in a loop

```powershell
$repo = 'C:\path\to\repo'
$jar  = 'E:\Chiru\code-knowledge-engine\dist\code-intelligence-engine.jar'
$modules = 'txn-report','acct-report','datmod'      # list your modules here; 'common' is added to each

foreach ($mod in $modules) {
  java -jar $jar pipeline -q `
    --source "$repo\$mod" --source "$repo\common" `
    --bytecode "$repo\$mod\target\classes" --bytecode "$repo\common\target\classes" `
    --exclude "**/target/**" --exclude "**/src/test/**" `
    --output "C:\analysis\$mod"
  "{0}: exit {1}" -f $mod, $LASTEXITCODE
}
```

Exit codes: `0` finished; `3` a strict validation gate failed (only with `analyze --strict`); `4` finished but business questions remain (only with `--require-complete`).

---

## 4. What you get in each output folder

Start with these, in this order:

| File | What it answers |
|---|---|
| `PIPELINE_STATUS.md` | Did every step run? What is left to do? |
| `F01_Business_Overview.md` | What the module does, in business words |
| `F07_Field_Reference.md` | **One section per output field**: position, what changes its value (zone, transaction type, codes), the path back to `table.column`, the formatting steps with worked examples, the width check. Also an index from each column to the fields it feeds, and a "Width problems" list |
| `F03_Business_Scenarios_And_Rules.md` | The rules per kind of work, with conditions |
| `F04_Data_Dictionary.md` | Every field with its calculation, step by step |
| `F06_Questions_For_Business_Experts.md`, `NEEDS_INPUT.md` | What the code cannot say |
| `13_Analysis_Coverage_Report.md` | How much of the code the analysis covered |
| `analysis-model.json` | The full saved model (evidence with file and line for every fact) |

---

## 5. Check the run is trustworthy (do this on the first module)

1. Open `PIPELINE_STATUS.md`: every analysis stage should say `ok`.
2. Open `F07_Field_Reference.md` and count the fields in "Fields at a glance". **Does it match the number of fields in the record (for example 100)?** If it is lower, the writer's layout was only partly recognised: send one writer class to whoever maintains the engine.
3. Pick three fields you know well, one simple, one that depends on transaction type, one that is formatted. Compare their sections with the code.
4. Look at "Width problems". Each entry is either a real defect in the code or a column size worth confirming.
5. Look at `06_SQL_Analysis.md`. Every query the module runs should be listed, including those defined in XML. If SQL from XML is missing, check that `--source` points at the module root (section 2).
6. Check which fields say "not traceable". These need an answer from the business or the owner of the external call (see `NEEDS_INPUT.md`).

---

## 6. The AI step (GitHub Copilot or Claude)

The engine produces facts; the AI only words them. Every reply is checked: each number, quoted value and label must appear in the
facts it cites, and anything else is dropped.

1. Open the output folder in VS Code (or any editor with Copilot agent mode).
2. Ask the agent: *"Follow ai/PROMPT.md. For every file in ai/pack, write the reply JSON to ai/responses with the same file name."*
   - `ai/pack/overview.json`, `scenario-*.json`, `open-questions.json`, `terms.json` give the story sections (`N01_AI_Narrative.md`).
   - `ai/pack/field-<name>.json` gives one pack per output field (`N02_Field_Derivations.md`). With 100 fields that is 100 files: use agent mode and let it loop.
3. Run the same `pipeline` command again. It checks the replies and writes `N01_AI_Narrative.md`, `N02_Field_Derivations.md` and `ai/verification-report.md`.
4. Read `ai/verification-report.md`. Rejected paragraphs are listed with the reason; the facts behind them are kept unchanged in the documents.

---

## 7. Answer the questions, then rebuild

1. Open `NEEDS_INPUT.md`. Copy the lines from `context\answers.template.properties` into `context\answers.properties` and fill them in. Names for constants go in `context\glossary.properties`.
2. Run the same command again. Everything is rebuilt with your answers. Your two files are never overwritten.

---

## 8. Practical tips for the 30-module layout

- **Do one TXT module first**, review it (section 5), then run the loop for the rest.
- **The `common` module** is analysed 30 times. That is expected and cheap; each run is independent.
- **Zones, operation codes, event types, transaction types:** they appear in F07 under "What changes the value of a field" as the attributes the code tests, with the conditions. The names shown are the identifiers used in the code, so well-named variables give readable documents; add business names in `context\glossary.properties`.
- **DAT module:** same command. A field is recognised when the code writes it at a fixed position, whatever the file extension.
- **One file per iteration:** the documents describe the layout of one record and one output file; the loop that creates one file per iteration shows up in `F02_End_To_End_Business_Flow.md`.
- **Dependencies on a library you do not own** (a vendor JAR): calls into it show as "comes from outside the analysed code". Add the jar's classes with `--bytecode` only if you want them in the call graph; their source is not analysed.

---

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `UnsupportedClassVersionError` | Running with a JDK older than 21 | Install JDK 21+ and check `java -version` |
| Many fields "not traceable" | `common` not included, or the helper lives in another module | Add that module with another `--source` |
| SQL from XML or `.properties` missing | `--source` points at `src\main\java` | Point at the module root |
| Fewer fields than expected | The record is built in a way the engine does not recognise yet | Send one writer class for analysis; meanwhile check `13_Analysis_Coverage_Report.md` |
| A field has no formatting steps | The formatting helper uses something outside the recognised idioms (padLeft/padRight, `String.format`, `movePointRight`, `setScale`, `longValue`, `trim`, case changes) | The field's position and source are still documented; steps are never guessed |
| Out of memory on a very large module | Default JVM heap | Run with `java -Xmx4g -jar ...` |
| Parse warnings in the console | Syntax the parser does not know | Listed in `12_Open_Questions_And_Gaps.md`; the rest of the run is unaffected |

---

## 10. Quick checklist

- [ ] JDK 21+ and the jar are in place
- [ ] `mvn -q -DskipTests compile` done in the target project (optional)
- [ ] Module list decided, `common` added to each run
- [ ] `--source` points at module roots
- [ ] First module run, `PIPELINE_STATUS.md` all ok
- [ ] Field count in F07 matches the record
- [ ] Three fields compared with the code
- [ ] AI replies saved, pipeline re-run, `verification-report.md` read
- [ ] `NEEDS_INPUT.md` answered, pipeline re-run
