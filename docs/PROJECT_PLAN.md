# Project Plan

## Team roles

The work divides along the three layers of the application. The shared `model` package is
agreed in week 1 and changed only by agreement.

| Member | Area | Owns | Tests owned |
|---|---|---|---|
| **A: Persistence** | MySQL schema, JDBC DAO layer, configuration, data import | `dao/*`, `src/main/resources/sql/schema.sql`, `db.properties.example`, `DataStore.importDataset` | `DataStoreTest` (H2 and MySQL runs) |
| **B: Solver engine** | conflict graph, pre-checks, backtracking, soft objective, annealing, sample and synthetic data, CLI | `solver/*`, `service/SampleData`, `service/Cli` | `BacktrackingSolverTest`, `FeasibilityCheckerTest`, `CliqueFinderTest`, `SoftConstraintEvaluatorTest`, `SimulatedAnnealerTest`, `TimetableEngineTest` |
| **C: Interface** | JavaFX application, forms and validation, generation progress, timetable views, CSV export | `ui/*`, `app.css`, `service/TimetableGrid`, `service/CsvExporter`, `service/TimetableService` | `CsvExporterTest`, GUI smoke runs |

### Contracts between members

Each member works against these interfaces, which are fixed early so the others can stub or
mock them:

- **A → B:** `Dataset` (config, rooms, faculty, batches, courses) from `DataStore.loadDataset()`.
  The engine never touches JDBC.
- **B → C:** `TimetableEngine.run(dataset, options, listener, cancelled)` returns a `Result`
  with an outcome, a log, a pre-check report and entries. Progress is streamed through
  `Listener` (log lines, stage names, annealing cost).
- **B → A:** `List<TimetableEntry>` (course, meeting index, day, period, room) for
  `TimetableDao.save(...)`.
- **C → everyone:** `TimetableService` holds the use cases (generate, save, evaluate,
  load sample data), so the UI holds no business logic.

## Week-by-week plan

| Week | A: Persistence | B: Solver engine | C: Interface | Milestone |
|---|---|---|---|---|
| 1 | ER model, `schema.sql` draft | problem model, meetings, conflict graph, `HardConstraintValidator` | wireframes, JavaFX shell, tab layout | model package frozen |
| 2 | `Database`, rooms/batches DAOs | domains (`Problem`), sample department | CRUD tabs on in-memory lists | |
| 3 | faculty DAO with unavailability, course DAO | backtracking with MRV + forward checking | faculty availability editor, course editor | |
| 4 | `DataStore.loadDataset`, timetable run DAO | per-slot room matching, DSatur tie-breaking, restarts | Generate tab on a background `Task`, live log | |
| 5 | settings DAO, transactions, FK error mapping | max-clique bound, Hall checks, readable explanations | timetable grid view (batch / faculty / room) | |
| **6** | **vertical slice** | **vertical slice** | **vertical slice** | **demo: enter data → MySQL → generate a clash-free timetable → view it → saved run reloads** |
| 7 | embedded H2 fallback | soft objective (idle gaps, load spread) + simulated annealing | cost chart, cancel button | |
| 8 | CSV export support queries | session spacing, building changes, weight tuning | weights in UI, CSV export | |
| 9 | `course_batch` join table | combined classes in model, solver and annealer | multi-batch course editor, "joint with" cells | combined classes end to end |
| 10 | MySQL integration test runs | brute-force property tests, benchmarks (`--synthetic`) | GUI smoke tests, usability fixes | feature freeze |
| 11 | packaging (fat jar), install guide | performance tuning, DESIGN.md | user guide, screenshots | release candidate |
| 12 | final report and presentation (all) | | | submission |

### Week 6 vertical-slice demo script

1. Start MySQL and the app (`mvnw javafx:run`). The header shows the MySQL connection.
2. Load the sample department, or type in two batches, three teachers (one with unavailable
   slots), four rooms and six courses.
3. Generate. The log shows the conflict graph, the clique bound and a SOLVED search.
4. Open the Timetable tab: no clashes, teacher unavailability respected, and the status bar
   confirms that the hard constraints are re-checked.
5. Restart the app: the saved run is still there (it is stored in MySQL).

## Traceability: proposal → implementation

| Proposal statement | Where it is implemented |
|---|---|
| JavaFX desktop application | `ui/TimetableApp` and the tab classes |
| MySQL through a JDBC DAO layer | `dao/*Dao` interfaces, `dao/Jdbc*Dao`, `schema.sql`, `DataStore` |
| each course decomposed into its weekly meetings | `solver/Problem` constructor, `solver/Meeting` |
| constrained graph colouring: meetings = vertices, conflicts = edges, slots = colours | `Problem.neighbourSet`, base domains, `BacktrackingSolver` assigns slots |
| backtracking with minimum-remaining-values ordering | `BacktrackingSolver.selectMeeting` / `compareUrgency` |
| saturation-degree tie-breaking | `BacktrackingSolver.saturation` (DSatur), maintained in `place` / `unplace` |
| forward checking | `BacktrackingSolver.forwardCheck` with trail and `undo` |
| maximum-clique lower bound detects provably infeasible inputs before search | `solver/CliqueFinder` (MCQ), `FeasibilityChecker.check`, engine stops on proof |
| simulated annealing on a weighted soft-constraint objective | `solver/SimulatedAnnealer`, `solver/SoftConstraintEvaluator` |
| batch idle gaps, faculty load spread, session spacing, building changes | `SoftConstraintEvaluator.idleGaps / loadSpread / sessionSpacing / buildingChanges` |
| every move rejected unless it preserves hard feasibility | `SimulatedAnnealer.free / teachable` checked before `apply`; final audit by `HardConstraintValidator` |
| honest handling of infeasibility | separate outcomes: INFEASIBLE_PRECHECK (with reasons), INFEASIBLE_SEARCH, TIMEOUT ("undecided"), CANCELLED |

Additions beyond the proposal: Hall's-condition checks with explanations, exact per-slot room
matching inside the search, randomised restarts, combined classes, re-validation of saved
timetables, CSV export, the embedded database fallback and the headless CLI.

## Risks and mitigations

| Risk | Mitigation in place |
|---|---|
| Solver too slow on real data | pre-checks catch hopeless inputs at once; restarts; time limit with an honest "undecided" result; benchmarks up to 1,100 meetings run in under 0.1 s of search |
| MySQL not available on lab or demo machines | embedded H2 database in MySQL mode, offered automatically |
| JavaFX packaging problems | Maven `javafx:run`, a fat jar with a non-`Application` launcher class, and the Maven Wrapper so no Maven install is needed |
| Subtle solver bugs | independent validator after every phase; brute-force property tests on 3,000 instances |
| Integration surprises between members | fixed contracts (above) from week 1; vertical slice in week 6 |
