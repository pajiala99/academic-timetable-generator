# SlotWise: notes for Claude

SlotWise is an automated academic timetable generator: a Java 17 project with a JavaFX desktop
app, a browser version, a MySQL (or embedded H2) database behind a JDBC DAO layer, and a
graph-colouring engine. Start with `README.md` (usage), `docs/DESIGN.md` (algorithms) and
`docs/HANDOFF.md` (project status, what is left, presentation script).

## Commands

```bash
./mvnw test                                   # all tests (about 15 s); needs no database
./mvnw -q package -DskipTests                 # target/slotwise-1.1.0-all.jar
java -jar target/slotwise-1.1.0-all.jar --embedded --sample          # desktop app
java -jar target/slotwise-1.1.0-all.jar --web --embedded --sample    # browser version, http://localhost:8080
./mvnw -q compile exec:java -Dexec.args="--print none"               # headless engine demo
```

`--embedded` uses the built-in H2 database in `~/.timetable/`. Without it the app connects to
MySQL using `db.properties` (template: `db.properties.example`). The DAO tests can target a
real MySQL with `-Dtest.mysql.url=... -Dtest.mysql.user=... -Dtest.mysql.password=...`; CI does
this on MySQL 8.4 (`.github/workflows/ci.yml`).

## Layout

- `model/`: records shared by every layer (`Course` has `batchIds` and `periodsPerMeeting`;
  `Batch.parentId` marks a lab group; `Faculty.maxPerDay` is the daily limit, 0 = none).
- `dao/`: `Database` (connection, schema, in-place upgrades), `DataStore`, DAO interfaces and
  `Jdbc*Dao` implementations. Schema: `src/main/resources/sql/schema.sql`.
- `solver/`: `Problem` (meetings, student groups, pieces), `FeasibilityChecker` +
  `CliqueFinder` + `BipartiteMatching` (infeasibility proofs), `BacktrackingSolver`,
  `SoftConstraintEvaluator`, `SimulatedAnnealer`, `HardConstraintValidator` (independent
  audit), `TimetableEngine` (pipeline).
- `service/`: `TimetableService` (use cases), `SampleData`, `TimetableGrid` (cell labelling
  shared by both front ends), `CsvExporter`, `PdfExporter`, `Cli`.
- `ui/`: JavaFX desktop app. `Launcher` is the jar's main class and switches to the web
  version on `--web`.
- `web/`: `WebServer` (JDK HttpServer on 127.0.0.1), `Api` (JSON routes), `GenerationJob`,
  `Http`. Pages: `src/main/resources/web/` (plain HTML/CSS/JS, no build step).

## Conventions and gotchas

- Target is **Java 17**: no pattern-matching `switch` or other Java 21 features.
- The solver must stay **sound and complete**. `BacktrackingSolverTest` compares it with a
  brute-force enumerator (`TinyInstances`) on 3,000 random instances; run it after any solver
  change. Pre-checks must only report *proven* infeasibility.
- Every hard constraint is checked in three places that must agree: the solver/annealer,
  `HardConstraintValidator`, and the brute-force enumerator in the tests.
- Tests hard-code sample-department numbers (126 meetings, 144 periods, clique bound 25).
  Update them if `SampleData.department()` changes.
- Adding a column: add it to `schema.sql` **and** to `Database.UPGRADES`, so existing
  databases are upgraded in place.
- Desktop and web front ends share `TimetableService` and `TimetableGrid`; put behaviour
  there, not in one front end.
- Commit messages end with a `Co-Authored-By:` line when Claude wrote the change.
