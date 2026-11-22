# Master Prompt: Java Code Intelligence Engine

## Role

Act as a Senior Software Architect, Java Static Analysis Expert, and AI
Automation Engineer.

Design and implement a production-quality, reusable automation called
**Java Code Intelligence Engine**.

The engine must accept an unknown Java repository, module, package, or
source directory and generate accurate functional documentation
explaining:

-   What functionality exists.
-   How each business scenario executes.
-   Which business rules and conditions are applied.
-   Which database tables and fields are read or changed.
-   How values move through Java classes and methods.
-   How every final output field is derived.
-   How final files, API responses, or database outputs are generated.

This is not merely a Java parser or call-graph generator. The central
objective is **end-to-end source-to-output traceability with evidence**.

## 1. Input scope

Accept: - Complete Java repositories and individual modules/packages. -
Java source files and compiled bytecode where available. - Maven and
Gradle project metadata. - XML, properties, YAML, configuration files. -
SQL files and SQL embedded in Java or XML. - ORM mappings, annotations,
MyBatis or equivalent mapper files. - Input/output layouts for TXT,
fixed-width, CSV, XML, and JSON. - Relevant schemas or DDL when
supplied.

Do not assume a particular Java framework, database, or business domain.
Support Java 8 and later where practical. Static analysis must not
require executing the target application.

## 2. Functional modules

### Module 1 --- Repository discovery

Discover and inventory: - Modules and submodules. - Packages, classes,
interfaces, abstract classes, methods, constructors. - Main methods and
other entry points. - Spring controllers, services, repositories, batch
jobs, scheduled jobs. - Configuration classes and external
dependencies. - Database connectivity and file/API input-output
locations.

Produce a repository inventory with source paths.

### Module 2 --- Java source analysis

Use Spoon as the primary Java source analysis library, or justify a
better alternative.

Extract: - Classes, methods, constructors, parameters, return types. -
Method invocations and object creation. - Variable declarations,
assignments, and field references. - IF/ELSE, SWITCH, loops, try/catch,
and exception paths. - Annotations, inheritance, interfaces, and
dependencies. - Expressions, arithmetic operations, type conversions,
and null handling.

Capture file paths and line numbers wherever available.

### Module 3 --- Execution-flow and bytecode analysis

Use SootUp for bytecode-level analysis and call graph construction where
useful.

Identify: - Entry points and reachable methods. - Method-to-method
relationships. - Call hierarchy and dependencies. - Control-flow
branches and exception paths. - Data passed through method arguments,
return values, and fields.

Generate Mermaid execution-flow diagrams.

Do not present a possible call relationship as a guaranteed runtime
execution sequence. Account for interfaces, dynamic dispatch,
reflection, dependency injection, callbacks, configuration-driven
execution, and runtime-generated behavior. Mark unresolved or uncertain
relationships explicitly.

### Module 4 --- SQL and database analysis

Use SQLGlot or another suitable SQL parser.

Identify: - SELECT, INSERT, UPDATE, and DELETE statements. - Tables,
columns, aliases, joins, join conditions, filters. - CTEs, subqueries,
grouping, ordering, and aggregations. - Prepared-statement parameters
and parameter-to-variable mappings. - Stored procedure and function
references. - Dynamic SQL and ORM-generated query references where
discoverable.

Search SQL in Java strings, XML, mapper files, annotations, properties,
and standalone SQL files.

For each table and column, record whether it is read, inserted, updated,
or deleted. Do not infer a database column mapping from matching
variable names alone; establish it through code and query evidence.

### Module 5 --- Business-rule extraction

Identify candidate business rules from: - Conditional branches and
nested conditions. - SWITCH cases. - Calculations and validations. -
Status transitions. - Date, amount, and threshold comparisons. - SQL
predicates. - Configuration-driven rules. - Combinations of methods and
conditions.

For each rule, capture: 1. Stable rule ID. 2. Rule name. 3.
Plain-language explanation. 4. Triggering condition. 5. Input fields and
variables. 6. Calculation or transformation. 7. Result and affected
output fields. 8. Applicable business scenario. 9. Source class, method,
file, and line range. 10. Related SQL and database fields. 11. Evidence
and confidence/status.

Distinguish deterministic extracted facts from AI-generated
interpretations. Never invent business meaning. Mark ambiguous rules for
human review.

### Module 6 --- End-to-end data lineage

This is the highest-priority capability.

For every output attribute, trace backwards through as many supported
links as possible:

`Output field → output variable/object → assignment/transformation → Java method → business rule/condition → intermediate variables → SQL parameter/result mapping → database table.column`

Support: - Direct mappings and renaming. - Type conversion and
formatting. - Numeric and date calculations. - Conditional derivations
and scenario-specific paths. - Multiple source fields contributing to
one output. - Aggregations and loops. - Defaults, null handling,
constants, and configuration values. - Values passed across method
boundaries. - API or file inputs and external service responses.

Also support forward tracing from a source field to impacted outputs.

Do not claim complete lineage if any link is missing. Record each link
with evidence and mark the trace as verified, partial, or unresolved.

### Module 7 --- Business-scenario analysis

Discover scenarios from actual code and configuration. Examples such as
DISPATCH, STORAGE, RETURN, REVERSAL, and CANCELLATION are
illustrative only and must not be hardcoded.

For each discovered scenario, document: - Entry point and trigger. -
Methods and branches involved. - Tables and columns accessed or
changed. - Business rules applied. - Calculations and transformations. -
Output fields affected. - Final output destination.

Document separate derivation paths when the same output field is
calculated differently across scenarios.

### Module 8 --- Output analysis

Identify how outputs are produced: - TXT and fixed-width files. - CSV,
XML, and JSON. - Database writes. - API responses. - Other file formats.

For fixed-width files, extract attribute name, description (if
available), start position, end position, width, data type, precision,
formatting, padding, defaults, and derivation.

Trace every output field to its source and applicable rules.

### Module 9 --- Documentation generation

Generate Markdown documentation, using tables and Mermaid diagrams where
useful.

Required documents: 1. `01_Module_Overview.md` 2.
`02_Functionality_Inventory.md` 3. `03_Execution_Flow.md` 4.
`04_Business_Rules.md` 5. `05_Database_Inventory.md` 6.
`06_SQL_Analysis.md` 7. `07_Field_Mapping.md` 8.
`08_Source_To_Output_Traceability.md` 9.
`09_Output_File_Specification.md` 10. `10_Class_Method_Dependencies.md`
11. `11_Business_Scenario_Analysis.md` 12.
`12_Open_Questions_And_Gaps.md` 13. `13_Analysis_Coverage_Report.md`

Every important technical statement must include source file and line
references wherever available.

## 3. Field-level documentation standard

For every output field, provide:

  Attribute         Required information
  ----------------- ----------------------------------------
  Output field      Final attribute name
  Position          Start, end, and width, if applicable
  Source table      Actual database table, if established
  Source column     Actual database column, if established
  SQL query         Query ID or source reference
  Java class        Processing class
  Java method       Processing method
  Input variables   Relevant inputs
  Business rule     Related rule ID
  Condition         Exact condition or branch
  Derivation        Detailed calculation or transformation
  Output format     Type, precision, padding, formatting
  Scenario          Applicable business flow
  Evidence          File path and line range
  Trace status      Verified, partial, or unresolved

If derivation differs by scenario, document each path separately.

A reader must not have to guess where a value came from or how it was
calculated.

## 4. Technical architecture

Use a modular architecture. Suggested technologies:

-   Python for orchestration, workflow, validation, and documentation
    generation.
-   Spoon for Java source parsing.
-   SootUp for bytecode-level analysis and call graphs.
-   SQLGlot for SQL parsing.
-   NetworkX or JGraphT for dependency and lineage graphs.
-   Mermaid for diagrams.
-   An LLM for plain-language explanations of evidence already
    extracted.
-   JSON as the intermediate analysis representation.
-   Markdown as the primary documentation output.

Evaluate compatibility and maintenance before choosing library versions.
Do not assume a library supports every Java version or construct.
Explain alternatives and trade-offs.

Keep source parsers, bytecode analysis, SQL analysis, lineage
construction, LLM interpretation, and documentation generation
independently testable.

The LLM must explain extracted facts; it must not replace deterministic
extraction or fabricate missing links.

## 5. Intermediate data model

Create a versioned, structured model for: - Repository and module. -
Package, class, method, and invocation. - Variable, field, expression,
and control-flow branch. - SQL statement, table, column, and
parameter. - Business rule and business scenario. - Transformation and
data-lineage relationship. - Output file and output field. - Evidence,
source location, confidence, and trace status.

Use stable IDs and explicit typed relationships. Preserve provenance for
every extracted fact.

The model must support: - Source-to-output tracing. - Output-to-source
tracing. - Scenario-based tracing. - Business-rule lookup. - Table and
column usage lookup. - Regeneration of documentation without repeating
all analysis where possible.

Do not generate Markdown directly from raw parser output. Build and
validate the intermediate model first.

## 6. Validation and coverage

Implement checks such as: 1. Every discovered output field has a
traceability status. 2. Every documented rule has source evidence. 3.
Every database table/column claim has SQL, ORM, or schema evidence. 4.
Every execution relationship has supporting evidence and an appropriate
certainty level. 5. Missing links are reported. 6. Dynamic SQL and
unsupported constructs are flagged. 7. Duplicate or conflicting rules
are highlighted. 8. Scenario-specific derivations are not merged
incorrectly. 9. Verified facts are distinguished from inferred
explanations. 10. Unsupported constructs are never silently omitted.

Generate coverage metrics: - Classes and methods discovered/analyzed. -
SQL statements discovered and parsed. - Tables and columns identified. -
Business rules extracted. - Scenarios identified. - Output fields
identified. - Fully traced, partially traced, and unresolved fields. -
Unsupported constructs and analysis warnings.

Coverage percentages must state their denominator and must not imply
correctness where evidence is incomplete.

## 7. Implementation phases

Do not implement everything in one attempt.

**Phase 1 --- Assess and design** - Inspect the existing repository. -
Summarize its structure and constraints. - Propose architecture,
component responsibilities, technology choices, folder structure, data
model, risks, and phased plan. - Do not make major changes before
presenting the design.

**Phase 2 --- Foundation** - Create project structure, interfaces,
configuration, logging, error handling, and versioned intermediate
model.

**Phase 3 --- Repository discovery and Spoon parsing** - Implement
discovery and source extraction. - Add unit tests and sample outputs.

**Phase 4 --- Method relationships and SootUp** - Implement bytecode
analysis and call relationships. - Document limitations and unresolved
dynamic behavior.

**Phase 5 --- SQL analysis** - Parse SQL and map query
parameters/results where possible. - Add tests for joins, aliases,
subqueries, CTEs, and dynamic SQL limitations.

**Phase 6 --- Business rules** - Extract conditions, expressions,
candidate rules, and evidence.

**Phase 7 --- Data lineage** - Connect source fields, SQL mappings, Java
variables, transformations, rules, scenarios, and outputs.

**Phase 8 --- Scenario analysis** - Separate and document
scenario-specific execution and derivation paths.

**Phase 9 --- Documentation** - Generate all Markdown documents and
Mermaid diagrams from the intermediate model.

**Phase 10 --- Validation and hardening** - Add coverage reports,
integration tests, error handling, incremental analysis where practical,
and documentation.

After each phase, run relevant tests, fix failures, update the README,
and show sample outputs. Do not proceed past a phase with unresolved
critical defects without explaining and obtaining approval.

## 8. Sample application and acceptance tests

Create a small sample Java application with: - Approximately 5--10
classes. - Multiple methods and method calls. - SELECT and UPDATE SQL. -
At least three distinct business scenarios. - Conditional rules and
nested branches. - Numeric calculations and formatting. - Multiple
source fields contributing to one output field. - Fixed-width TXT
generation.

Use it to verify the entire pipeline. Include expected results and
automated tests for each major component.

The sample should demonstrate both forward and reverse lineage,
including a case where lineage is partial and correctly reported as
unresolved.

## 9. Engineering standards

-   Reusable components and clean architecture.
-   SOLID principles where appropriate.
-   Python type hints and clear interfaces.
-   Structured logging and meaningful exceptions.
-   Configurable analysis settings.
-   No hardcoded business rules or schemas.
-   No dependency on a specific target Java project.
-   Unit and integration tests.
-   Clear setup instructions and README.
-   Pin and document compatible dependency versions.
-   Preserve source evidence and analysis provenance.
-   Never silently treat an approximation as a verified fact.

## 10. Working instructions for Claude Code

First inspect the current repository and report: 1. Repository
assessment. 2. Proposed architecture. 3. Component responsibilities. 4.
Technology choices and justification. 5. Folder structure. 6.
Intermediate data model. 7. Implementation phases. 8. Risks,
limitations, and assumptions.

Wait for approval before major implementation.

After approval, implement one phase at a time. Before editing files,
explain the intended changes. After each phase, report files changed,
tests executed, test results, known limitations, and the next phase.

Do not create large amounts of placeholder code. Implement working,
tested vertical slices.

## Final acceptance criterion

Given an unknown Java module as input, the engine must identify its
functionality, execution relationships, business rules, database tables
and fields, transformations, scenarios, and final outputs to the extent
supported by available evidence.

A developer must be able to select any output field and trace backwards
through every available processing step to its original source, with
source-code evidence.

The result must be accurate, explainable, reusable, testable, and
suitable for functional documentation.

**The most important success metric is not how many Java classes can be
parsed. It is how accurately the engine explains the derivation of every
final output field.**
