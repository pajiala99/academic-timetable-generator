# Design and Algorithms

This document describes how the timetable generator models the problem, the algorithms it
uses, why they are correct, how they were verified and what they cannot do yet. Code
references point to `src/main/java/edu/timetable/`.

## 1. Problem model

### 1.1 Entities

| Entity | Attributes | Code |
|---|---|---|
| Room | code, building, capacity, type (LECTURE / LAB) | `model/Room` |
| Batch | name, number of students (a group that attends everything together, e.g. CSE-3A) | `model/Batch` |
| Faculty | name, set of unavailable slots | `model/Faculty` |
| Course | code, title, faculty member, **one or more batches**, meetings per week, required room type | `model/Course` |
| Week | `days × periodsPerDay` slots (default 5 × 7 = 35) | `model/SchedulingConfig` |

A course with several batches is a **combined class**, such as a common lecture or a shared
elective. Every attending batch is present at every meeting, so the room must seat all of them.

### 1.2 Meetings, the conflict graph and domains (`solver/Problem`)

- Each course *c* with *k_c* weekly meetings becomes *k_c* **meetings**. These are the
  vertices of the conflict graph.
- Two meetings **conflict** (edge) when they share a batch or a faculty member. Adjacent
  meetings must receive different **slots**, which play the role of colours.
- Each meeting *m* has a **base domain** `D(m) = A(m) × S(m)`, where `A(m)` is the set of
  slots its teacher is available for and `S(m)` the set of rooms of the right type with
  enough seats. The domain is a product, and both the solver and the annealer exploit this.

The sample department has 136 meetings and 1,943 edges. The largest synthetic benchmark has
1,101 meetings and 20,815 edges.

### 1.3 Hard constraints

| | Constraint | Enforced by |
|---|---|---|
| H1 | every meeting gets exactly one slot and one room | search (complete assignment) |
| H2 | no batch or faculty member is in two meetings at once | conflict edges |
| H3 | no room hosts two meetings at once | per-slot room matching |
| H4 | room type matches and capacity ≥ total students of all attending batches | base domain |
| H5 | the teacher is available in the meeting's slot | base domain |

### 1.4 Soft constraints (`solver/SoftConstraintEvaluator`)

All four terms are sums of independent per-entity contributions. That is what makes
incremental evaluation possible (section 5).

| Term | Definition (per entity, summed) | Zero means |
|---|---|---|
| Batch idle gaps | for each batch *b* and day *d*: `(last − first + 1) − n_bd` free periods between the first and the last class | compact days |
| Faculty load spread | for each teacher: `Σ_d c_d² − (r(q+1)² + (D−r)q²)` with `q = ⌊N/D⌋`, `r = N mod D` | the most even daily split possible |
| Session spacing | for each course: `Σ_d [C(n_d, 2) + 0.25 · n_d · n_{d+1}]` (pairs on the same day, pairs on consecutive days) | sessions spread out |
| Building changes | for each batch and day: consecutive classes held in different buildings | no walking between blocks |

The sum of squared daily loads is convex and minimised exactly by the most even split.
Subtracting that minimum makes 0 mean "as even as possible" whatever the teacher's total load.
The objective is the weighted sum of the four terms, with default weights 3, 1, 4 and 2. All
weights can be changed in the Generate tab.

## 2. Complexity and structure

The decision problem "is there a timetable satisfying H1–H5?" is NP-complete. It contains
graph k-colouring (Karp, 1972), and even the restricted class–teacher problem becomes
NP-complete once teachers have unavailable periods (Even, Itai & Shamir, 1976).

Without combined classes, the structure has a useful consequence. If every course has exactly
one batch, each meeting is an *edge* between its batch and its teacher in a bipartite
multigraph *G*, and the conflict graph is the **line graph** L(G). A family of pairwise
intersecting edges in a triangle-free graph shares a common endpoint, so every clique of
L(G) is a "star": the meetings of one batch or of one teacher. Hence **ω = Δ(G)**, the
heaviest weekly load of any batch or teacher. By König's edge-colouring theorem (1916) the
chromatic number is also Δ(G). Without availability and room constraints the problem is
therefore polynomial, and the clique bound is exact. The hardness comes from:

1. **teacher availability**, which turns the problem into list edge colouring (NP-complete);
2. **rooms**: capacities, types and a limited number of rooms per slot;
3. **combined classes**: a meeting attended by several batches is a hyperedge, so cliques
   no longer have to be stars.

The third point is why the generator runs a real maximum-clique search rather than counting
per-batch loads. Three electives E1 (batches A+B, 2 meetings), E2 (B+C, 2 meetings) and
E3 (A+C, 1 meeting) form a clique of 5. Yet no batch or teacher is in more than 4 of those
meetings, so in a 4-slot week every per-group count passes while the clique bound proves
infeasibility. This exact case is the test
`FeasibilityCheckerTest.cliqueBoundCatchesCombinedClassesThatGroupCountsMiss`.

## 3. Infeasibility pre-checks (`solver/FeasibilityChecker`)

Every check is a **necessary condition** for a timetable to exist. A failed check is therefore
a proof of infeasibility, and the engine stops before searching. Each failure comes with an
explanation in terms of the user's data.

1. **Empty domains.** A course needs a room type that does not exist, more seats than the
   largest room of that type, or a teacher who is never available.
2. **Maximum-clique bound** (`solver/CliqueFinder`). Branch and bound with a greedy-colouring
   upper bound (MCQ, Tomita & Seki, 2003). The incumbent starts as the largest batch or
   teacher group. A clique of ω mutually conflicting meetings needs ω distinct slots, so
   ω > |slots| is a proof of infeasibility. The search runs under a time limit (2 s by
   default). If it is cut short, the best clique found is still a valid lower bound and is
   reported as "not proven maximum".
3. **Hall's condition on slots.** The meetings of each batch, each teacher and the maximum
   clique must go into *distinct* slots, each one a slot it is allowed in. That is possible
   only if a matching covers all of them (Hall, 1935). If the maximum matching
   (augmenting paths, `solver/BipartiteMatching`) is too small, an alternating-path search
   from the unmatched meetings extracts a **Hall violator**: a set *A* of meetings whose
   allowed slots *N(A)* are fewer than |*A*|. That set is the explanation, for example
   "Part-timer: 3 meetings that must be in different slots can only use 2 slot(s) [Mon P1,
   Mon P2]". This check is stronger than the clique bound whenever availability restricts
   the domains.
4. **Hall's condition on rooms.** Every meeting needs its own (slot, room) pair. A matching
   from meetings to their allowed pairs must exist. This catches room shortages that no
   conflict reasoning sees, such as five unrelated meetings with one room and four slots.

These conditions are not sufficient: some infeasible inputs pass all of them, and then the
search has to decide. The test suite contains such instances (section 6).

**Why the pre-checks matter.** Backtracking is notoriously bad at pigeonhole-style
infeasibility. Proofs that n+1 pigeons do not fit into n holes require exponential size in
tree-like resolution (Haken, 1985), which is what a backtracking search explores. On the
over-subscribed sample (36 meetings for CSE-3A in a 35-slot week), the clique bound proves
infeasibility in **1 ms**. Search alone was still running after **17–26 million nodes** when
the 20 s and 30 s limits stopped it (CLI `--overloaded --no-precheck`; test
`BacktrackingSolverTest.pigeonholeInfeasibilityNeedsTheCliqueBound`).

## 4. Backtracking search (`solver/BacktrackingSolver`)

### 4.1 Search over slots, rooms by matching

The search assigns **slots only**. For every slot it maintains a bipartite matching between
the meetings placed in that slot and the rooms that suit them. A set of meetings can share a
slot exactly when the matching covers all of them, which is H3 + H4. Placing a meeting runs an
augmenting-path search (Kuhn's method) that may move other meetings of the same slot to
different suitable rooms. Removing a meeting leaves a valid matching for the rest.

Branching over rooms as well would multiply the branching factor by up to |rooms|, with mostly
interchangeable choices. During development an earlier version did exactly that: it branched
on (slot, room) pairs, with symmetry breaking over rooms of equal type and capacity. It timed
out after 10 s (about 1–2 million nodes) on room-scarce 12-batch instances that the
matching formulation then solved in 14–548 ms.

### 4.2 Heuristics

- **Variable ordering:** *minimum remaining values*, the meeting with the fewest slots left.
  Ties go to the highest **saturation degree** (distinct slots already used by assigned
  neighbours, as in DSatur, Brélaz 1979), then to the most unassigned neighbours, then to
  the fewest suitable rooms, then at random.
- **Value ordering:** *least constraining value*. The solver first tries the slot that the
  fewest unassigned neighbours could still use, then the slot with the most free rooms, with
  random tie-breaking.

### 4.3 Forward checking

After a meeting *m* is placed in slot *s*, every unassigned meeting *n* that still has *s* in
its domain loses *s* if

- *n* conflicts with *m* (shares a batch or teacher), or
- the room matching of *s* can no longer absorb *n*. This is tested with an augmenting-path
  search that commits nothing, with a fast path when a suitable room is simply free.

If any domain becomes empty (a *wipe-out*), the solver backtracks immediately (Haralick &
Elliott, 1980). Removals are recorded on a trail as (meeting, slot) pairs, so undo costs O(1)
per removed value.

### 4.4 Restarts and what the result means

Runs are cut off after a node budget of 1,000 × 1.3^k nodes for restart *k*, with fresh random
tie-breaking each time. This tames the heavy-tailed run-time distributions typical of
backtracking (Gomes, Selman & Kautz, 1998). The three outcomes mean strictly different things:

- **SOLVED**: a complete assignment. It is re-checked by the independent validator.
- **INFEASIBLE**: a run *exhausted its search tree within its budget*. Every pruning rule
  (forward checking and the matching test) only removes values that cannot be part of any
  solution, so this is a proof that no timetable exists.
- **TIMEOUT**: the time limit was hit. The answer is unknown, and the app says
  "undecided", never "infeasible".

## 5. Simulated annealing (`solver/SimulatedAnnealer`)

The annealer starts from the search's clash-free timetable and keeps occupancy tables
`batchAt[batch][slot]`, `facultyAt[teacher][slot]` and `roomAt[room][slot]`.

- **Moves.** *Relocate* moves a meeting to a random allowed slot, into the first free suitable
  room found from a random offset. *Swap* exchanges the slots and rooms of two meetings. The
  partner comes from a batch of the first meeting 70% of the time, and from anywhere
  otherwise. Swaps between meetings of the same course are skipped because those meetings
  are interchangeable.
- **Feasibility filter.** Before a move is applied, the annealer checks it against the
  occupancy tables and the base domains. This takes O(number of batches of the meeting)
  lookups. Infeasible proposals are rejected outright, so **every state visited satisfies
  H1–H5**. In practice 60–80% of proposals are rejected this way, because a typical batch
  already uses 65–80% of its slots.
- **Incremental cost.** The change in cost is the cost of the affected entities after the
  move minus before. Those entities are the batches, teachers and courses of the one or two
  meetings moved, so the work does not grow with the size of the timetable. The test
  `localCostDeltaMatchesFullRecomputation` checks 2,000 random moves against a full
  recomputation.
- **Schedule.** The Metropolis acceptance rule is used with geometric cooling from T0 to
  T0/1000. T0 is calibrated from 400 sampled moves so that an average uphill move is accepted
  half the time (`T0 = mean uphill Δ / ln 2`). The best timetable seen is returned. The
  running cost is re-synchronised every 20,000 iterations to remove floating-point drift. A
  given seed always produces the same result.
- **Effect.** On the sample, soft cost goes from 441 to 41 in 1,000,000 iterations (about
  0.4 s): idle gaps 34 → 0, building changes 42 → 0, uneven teacher load 88 → 2. Larger
  instances improve by 80–85%. More iterations help on large inputs.

## 6. Verification

- **Independent validator** (`solver/HardConstraintValidator`). It re-checks H1–H5 directly
  from the dataset, without using the conflict graph or the domains. The engine runs it
  after search and after annealing, and treats any violation as an internal error instead of
  saving the result. The Timetable tab runs it again against the *current* data every time a
  saved timetable is shown.
- **Property tests against brute force** (`BacktrackingSolverTest`). On 3,000 random small
  instances, some with combined classes, an exhaustive enumerator decides feasibility. The
  solver must return SOLVED with a valid timetable on each of the 746 feasible ones, and
  INFEASIBLE on each of the 2,254 infeasible ones. This checks both soundness and
  completeness: an over-eager pruning rule would show up as a missed solution. Six of the
  infeasible instances pass every pre-check, so only the search proves them.
- **Pre-check soundness**: on 600 random instances, no feasible instance is ever reported
  infeasible.
- **Clique finder**: matches exhaustive subset enumeration on 300 random graphs, and returns
  genuine cliques on large instances.
- **Soft objective**: hand-computed values for every term, plus the delta-consistency
  property above.
- **Annealer**: never breaks a hard constraint on a tight instance, improves the sample by
  more than 50%, and is reproducible for a given seed.
- **Persistence**: all DAO tests run on H2 in MySQL mode by default, and pass on
  **MySQL 8.0.46** with `-Dtest.mysql.url=...`. They cover round trips, combined classes,
  unavailability updates, foreign-key protection, unique codes and the re-evaluation of a
  saved timetable after a room it used is deleted.
- **GUI**: during development the real JavaFX application was driven programmatically under
  a virtual display, against both the embedded database and MySQL. The runs covered adding a
  room, a teacher with unavailable slots and a combined course, a validation error, a refused
  deletion, generation, and all three timetable views. Screenshots in `docs/screenshots` come
  from those runs.

## 7. Persistence design (`dao/`)

```
faculty        1 ── * faculty_unavailability (day, period)
faculty        1 ── * course
course         * ── * batch                    via course_batch (course_id, batch_id)
timetable_run  1 ── * timetable_entry (course_id, meeting_index, day, period, room_id)
timetable_entry * ── 1 course,   timetable_entry * ── 1 room
settings: key/value pairs (shape of the week)
```

- **Foreign keys** use `ON DELETE CASCADE` for rows that belong to their parent
  (unavailability, course–batch links, timetable entries). They *restrict* deletion where a
  cascade would silently change meaning: a batch or teacher that still has courses. The UI
  turns those refusals into plain-language messages (`DataAccessException.isConstraintViolation`).
- **Transactions** wrap every multi-table write: a teacher with their unavailability, a
  course with its batches, a timetable run with its entries.
- **DAO interfaces** (`RoomDao`, `CourseDao`, ...) with JDBC implementations sit behind a
  `DataStore`. The engine only ever sees a `Dataset` record, so it does not know about the
  database.
- **Configuration** comes from `db.properties` or environment variables. The schema is
  created at start-up (`schema.sql`, idempotent). An **embedded H2 database in MySQL mode**
  is offered when MySQL is unreachable.

## 8. Limitations and future work

- Meetings are single periods. Double-period labs would need "same day, consecutive periods"
  constraints between meetings.
- Batches cannot be split into lab groups, and there are no room preferences or penalties for
  wasted seats, so a large hall may host a small class.
- There is no hard maximum of teaching periods per day. Uneven loads are only discouraged
  through the load-spread term.
- Every run re-plans all meetings. Pinning meetings and repairing an existing timetable
  incrementally would be valuable after small data changes.
- The search uses forward checking only. Stronger propagation (for example matching-based
  all-different filtering per batch, Régin 1994), conflict-directed backjumping or nogood
  learning would help on the hardest inputs.
- The annealer could gain Kempe-chain moves and tabu search. Comparing against a CP-SAT or
  ILP model would show how far from optimal the soft cost is.
- The clique search is worst-case exponential. It is time-limited and reports when its bound
  is not proven maximum.

## References

- Brélaz, D. (1979). New methods to color the vertices of a graph. *Communications of the ACM* 22(4).
- de Werra, D. (1985). An introduction to timetabling. *European Journal of Operational Research* 19(2).
- Even, S., Itai, A. & Shamir, A. (1976). On the complexity of timetable and multicommodity flow problems. *SIAM Journal on Computing* 5(4).
- Gomes, C. P., Selman, B. & Kautz, H. (1998). Boosting combinatorial search through randomization. *AAAI-98*.
- Haken, A. (1985). The intractability of resolution. *Theoretical Computer Science* 39.
- Hall, P. (1935). On representatives of subsets. *Journal of the London Mathematical Society* 10.
- Haralick, R. M. & Elliott, G. L. (1980). Increasing tree search efficiency for constraint satisfaction problems. *Artificial Intelligence* 14(3).
- Karp, R. M. (1972). Reducibility among combinatorial problems. In *Complexity of Computer Computations*.
- Kirkpatrick, S., Gelatt, C. D. & Vecchi, M. P. (1983). Optimization by simulated annealing. *Science* 220.
- König, D. (1916). Über Graphen und ihre Anwendung auf Determinantentheorie und Mengenlehre. *Mathematische Annalen* 77.
- Régin, J.-C. (1994). A filtering algorithm for constraints of difference in CSPs. *AAAI-94*.
- Tomita, E. & Seki, T. (2003). An efficient branch-and-bound algorithm for finding a maximum clique. *DMTCS 2003*, LNCS 2731.
