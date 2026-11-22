# SlotWise: hand-over

Everything you need to carry on: in a Claude desktop chat, on another laptop, or with your team.

## What SlotWise is

A timetable generator for a whole department, matching the "First Choice" project proposal:
a JavaFX desktop application backed by MySQL through a JDBC DAO layer. It models timetabling
as graph colouring (meetings are vertices, conflicts are edges, time slots are colours). It
proves infeasibility with a maximum-clique bound and Hall's-theorem checks, finds a clash-free
timetable by backtracking (MRV, saturation-degree tie-breaking, forward checking), and improves
it with simulated annealing against weighted soft constraints. A browser version runs the
same engine.

## What has been built

| Stage | What | Where |
|---|---|---|
| 1 | Engine, MySQL DAO layer, JavaFX app, combined classes, sample department, CSV export, CLI, 37 tests | first commit on `main` |
| 2 | CI on GitHub (JDK 17 tests + DAO tests on MySQL 8.4), MIT licence, README badge | `main` |
| 3 | Double-period labs, lab groups (CSE-1A G1/G2), teachers' daily limits, Save as PDF, long-session and daily-limit infeasibility proofs, in-place database upgrades | branch `feature/labs-groups-daily-limits`, merged into `main` |
| 4 | Name **SlotWise**, browser version (`--web`), 50 tests | same branch, merged into `main` |

Verification so far:
- All 50 automated tests pass.
- The DAO tests pass on a real MySQL server.
- The solver agrees with brute-force enumeration on 3,000 random instances.
- The desktop app was driven screen by screen.
- The web version was driven end to end in Chromium: forms, errors, generation, every view,
  PDF printing and phone width.

## How to run it

You need **Java 17 or newer** (for example Temurin 21 from https://adoptium.net).

**From a downloaded jar** (Mac: the `apple-silicon` build for M-chip Macs, `intel` otherwise):

```bash
cd ~/Downloads
java -jar slotwise-mac-apple-silicon.jar --embedded --sample          # desktop window
java -jar slotwise-mac-apple-silicon.jar --web --embedded --sample    # browser, http://localhost:8080
```

**From the code:**

```bash
git clone https://github.com/pajiala99/academic-timetable-generator.git
cd academic-timetable-generator
./mvnw -q package -DskipTests        # builds target/slotwise-1.1.0-all.jar for this computer
java -jar target/slotwise-1.1.0-all.jar --web --embedded --sample
```

The jar contains JavaFX for the operating system it was built on. Building it yourself always
gives the right one.

- `--embedded` uses the built-in database in `~/.timetable/`. Drop it to use MySQL as set in
  `db.properties` (see the README).
- `--sample` loads the sample Computer Science department into an empty database.

## Presentation demo (about 5 minutes)

1. **Start** the browser version (`--web --embedded --sample`). Show the dashboard: 6 batches
   plus 4 lab groups, 16 teachers, 49 courses, 126 weekly meetings.
2. **Data**:
   - Batches: CSE-1A is split into lab groups G1 and G2.
   - Faculty: Dr. Anita Rao is off on Fridays and limited to 3 periods a day.
   - Courses: labs are 2-period sessions, and HS102 is a combined class for CSE-1A and CSE-1B.
3. **Generate** and point at the log:
   - the clique lower bound of 25 periods, proven;
   - "Backtracking: SOLVED" in milliseconds;
   - the cost curve falling from about 500 to about 50;
   - "Hard constraints verified independently".
4. **Timetable**:
   - CSE-1A shows G1 and G2 in different labs at the same time, and no red cells.
   - Switch to Dr. Anita Rao: no Friday classes, at most 3 a day.
   - The green bar re-checks every hard constraint.
5. **Honesty about impossible inputs**: give CSE-3A three more courses (36 periods in a 35-slot
   week), or run `--overloaded` in the CLI. SlotWise proves it impossible instantly and says
   why. Mention that the search alone would run for millions of steps (`docs/DESIGN.md` §3).
6. **Print / Save as PDF** for one batch, or **Print every batch**. Download the CSV and open it
   in Excel.
7. If asked "why not just forms over a database": the problem is NP-complete. Point to the
   pre-checks, the search, the annealing and the brute-force verification in `docs/DESIGN.md`.

## What is left (optional)

- Rename the GitHub repository to `slotwise` (repository Settings → General → name). Old links
  keep working.
- Ideas, if there is time: drag-and-drop editing of a generated timetable with live clash
  checks; room preferences (avoid big halls for small classes); sessions that may not span a
  lunch break; a deployed web version with logins. `docs/DESIGN.md` §9 lists limitations.

## Continuing in a Claude desktop chat

1. Clone the repository (above) and open the folder in Claude Code (desktop app or terminal).
   Claude reads `CLAUDE.md` automatically, which explains the layout, commands and rules.
2. Paste this as the first message:

> This is SlotWise, our academic timetable generator (Java 17, JavaFX + browser version,
> MySQL/H2, graph-colouring engine). Read CLAUDE.md, README.md, docs/DESIGN.md and
> docs/HANDOFF.md first. Then run `./mvnw test` to confirm everything passes on this machine,
> and start the browser version with
> `java -jar target/slotwise-1.1.0-all.jar --web --embedded --sample` after building it.
> Next I want to: <describe what you want>.

## Team split (from the proposal)

| Member | Owns |
|---|---|
| Persistence | `dao/`, `schema.sql`, upgrades, DAO tests (also on MySQL) |
| Solver engine | `solver/`, `SampleData`, `Cli`, solver tests, benchmarks |
| Interface | `ui/` (desktop), `web/` + `resources/web` (browser), exports, front-end tests |

See `docs/PROJECT_PLAN.md` for the week-by-week plan and the proposal-to-code traceability
table.
