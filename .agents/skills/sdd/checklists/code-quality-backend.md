# Code quality checklist — backend

Load for the backend surface selected by [`SKILL.md`](../SKILL.md#setup). `CONTRIBUTING.md` §Scala composition and `DESIGN-PRINCIPLES.md` §1/§4/§5 own the general rules; cross-stack items live in [`code-quality.md`](code-quality.md).

- [ ] Every `F` API names its required I/O, resource, concurrency or capability. Pure operations return plain values through pure traits. `.pure[F]` may lift a branch of an effectful workflow; it does not justify an otherwise pure API.
- [ ] Domain files declare types, data, refinements and pure derivations of their owned facts, following `DESIGN-PRINCIPLES.md` §2. External representation parsing/validation and source-inventory assembly belong to adapters; business aggregate construction, lifecycle decisions and normalization belong to their owning application operations.
- [ ] Generic capabilities (`Logger`, clock, identity generation) are requested through `F` context bounds and typeclass accessors. Business ports and collaborators are explicit factory/private-constructor parameters. Concrete logging details belong in composition, not business APIs.
- [ ] Inventory every constructor and method parameter by lifetime. Fixed collaborators/configuration are captured once by the private Live implementation; operation and helper parameters are changing invocation data.
- [ ] Pure business operations return their own data; callers assemble workflow or presentation state. Independently changing algorithms are injected into their orchestrator, not implemented as its private algorithm collection.
- [ ] Configurable business data follows its agreed lifecycle: caller-owned repository contracts for repository-backed policy, or typed construction settings for static configuration loaded by composition. Trace authority, read/update owner and failure semantics; embedded tables or resource relocation do not establish configurability, and static settings do not replace a required repository.
- [ ] Concrete interpreters and effect runtimes are composed at the application boundary, not selected from inside domain code.
- [ ] In runtime composition, keep constructor/factory calls on one physical line; extract nested dependency construction into meaningfully named vals to fit the line limit.
- [ ] Business and adapter behavior lives in its owning injected module, not public helper callables. Inventory every public/package-visible companion or helper callable: it is a factory or a mechanical pure utility with no business-policy responsibility. Cite callers and the invariant shared by any utility; statelessness alone does not make business logic a utility.
- [ ] A shared codecs object contains pure bidirectional mappings only. Name at least two real consumers and the representation invariant whose divergence would be a bug; keep other codecs local to their owning implementation.
- [ ] Live implementations are `private final class`.
- [ ] Driven ports return domain-valid data. Adapters enforce the port contract; use cases do not re-check a guarantee the port already gives (for example, calling a list and then checking its first entry's kind).
- [ ] State-dependent writes check and commit against the same current state. Record the guarantee in owning contract documentation and test concurrent calls at the real adapter; an earlier service read is not sufficient.
- [ ] Pure and effectful business modules use a caller-defined trait, companion factory and private final Live implementation.
- [ ] Orchestration remains visible in service methods; effect sequencing is linear and errors remain explicit.
- [ ] Resource acquisition, use, and release follow the selected Typelevel effect library's resource-safe primitive.
- [ ] Name arguments whose types do not reveal their role, including literals, collections, `Nil`, booleans and optional values, in constructors and public/private calls alike. Self-describing domain values remain positional.
- [ ] Production dependencies are introduced only for a documented capability and kept out of pure domain modules.
- [ ] Destructure only when every field is required; otherwise access named fields. Tuple callbacks use `case (fieldA, fieldB)` when both are needed; positional accessors such as `._1` are not allowed.
- [ ] Each statement in a for-comprehension occupies one physical line. Extract multiline SQL and expressions into named vals or private defs before sequencing them.
- [ ] Use `.map` for a single generator with a pure result; reserve for-comprehensions for multiple dependent steps. Inventory every changed comprehension against this rule.
- [ ] Inventory comprehensions mixing `<-` and `=` against the fractal-design check. Keep effectful orchestration separate from lower-level transformations through meaningful owning operations, rather than wrapping pure values in effects. Retain a mix only with a concrete same-abstraction-level justification recorded in review evidence.

## Declarations and ordering

- [ ] Order everything most important first: parameters, methods, nested types, APIs and declarations. In a module: trait → companion `make` → private final Live → companion-owned result types.
- [ ] Every computational/helper method name contains an action or predicate verb. Inventory all declarations, including private helpers and suite-local builders, rather than checking only changed names.
- [ ] Result types live in the companion of the trait whose methods return them. Top-level types are only for config or shared values such as `AlbumPageSize`.
- [ ] Private members omit return types unless the type is `Unit` or the member is public API.
- [ ] For every new or changed trait operation, identify its operation-owned result enum after unwrapping `F`/`Db`; `Either`, `Option`, `Unit` and bare success values fail this gate.
- [ ] Failure enums inside a companion are named `Failure`, not repeated with the operation name.
- [ ] Default arguments appear only in tests; runtime code uses wartremover `DefaultArguments` and scalafix `DisableSyntax.noDefaultArgs` as enforced gates.
- [ ] A wartremover or scalafix suppression is allowed only when the flagged operation is provably safe at that site (for example `minBy` after a non-empty guard). It is scoped to the smallest declaration, uses the full wart id, and has source-backed justification in the review evidence under `DESIGN-PRINCIPLES.md` §8. Never suppress a gate to hide a real defect.
- [ ] Real imports are used, not fully-qualified paths.
- [ ] Optional values use the `maybe` prefix.

## Refinement placement and proof cost

- [ ] Inventory changed Iron refinement declarations and explicit/implicit construction sites (`autoRefine` included); identify each invariant and consumer. Outside production `domain`/`usecases`, including tests in mirrored packages, retain refinements only for a required guarantee; use underlying types for transport, rendering and temporary data that needs none.
- [ ] Adapters still validate incoming data into domain-valid port results, and domain-valued test inputs/collaborator responses retain their constraints. Reuse suite-local typed values or existing checked construction, validating before the tested call rather than bypassing an invariant.
- [ ] Expected standalone text/numeric catalogs and tables use underlying types, with actual refined outputs safely widened for comparison. For whole-domain outcomes, reuse already-typed arranged fields instead of re-proving identical literals, preserving complete result variants, flags, order and associations.
- [ ] Review bulk fixtures and repeated literals for proof-site multiplication, counting construction/conversion sites rather than aliases, field declarations or runtime records. Reuse existing checked setup/iteration sites where possible; preserve validation-specific proofs and rejection scenarios.
- [ ] Record `file:symbol` evidence for each retained refinement in tests/non-core code: the required guarantee and why underlying or already-typed values do not suffice; unjustified sites block review. Cost reductions preserve core types, constraints, full assertions and strict gates, with no unchecked casts/`assume` or new fixture framework.

## Syntax

- [ ] Use cats syntax: `x.pure[F]`, `.some`, `.asRight`, `.as`, `traverse_`. Do not write `F.pure(x)` or `IO.pure(x)`.
- [ ] Pure values go at the end of a chain, not in the middle.
- [ ] Lines stay within 150 columns (scalafmt `maxColumn`).
- [ ] Inspect every assignment, collection chain and lambda after formatting. Keep expressions that fit on one line together; multiline lambdas use `:` or braces rather than dangling parenthesized bodies. Formatter success alone does not establish this item.
- [ ] Scalafmt dialect scala3future rejects `traverse_: x =>`; write `traverse_ { x => ... }`.
- [ ] Inline shallow helpers that add no meaning beyond the code they wrap, however many call sites they have. A helper stays only when it holds non-trivial logic that would be buggy to diverge.
- [ ] Each method stays within about 7 to 10 lines. Split only when a unit has a real responsibility.

## Logging

- [ ] `Main` declares `implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")`.
- [ ] Do not contort a test to cover glue code. Keep coverage honest and exclude glue explicitly with `$COVERAGE-OFF$` markers where the repo already does.

## Test structure

- [ ] Test names start with "should" and describe high-level acceptance behavior.
- [ ] Each suite names an existing production module and exercises its public contract. Type declarations and implementation helpers do not acquire fictional component suites. Fold tests covering one flow into one scenario; when the module changes, reshape its suite rather than add coverage-only tests.
- [ ] Apply the shared checklist's seam definition before choosing a suite tier. Use `ComponentTest` for application operations with substituted ports and `SeamIntegrationTest` for an adapter crossing its actual external boundary. Name the file after that owner; package placement or a remote dependency elsewhere does not establish a seam.
- [ ] Test data is deterministic and suite-local. A module with collaborators has a local `buildX` creating anonymous substitutes, with observation `Refs` first when present and only scenario-varying responses as inputs; a pure module without collaborators needs no builder. Apply the architecture gate to builders; reusable setup owns meaningful scenario construction, not fixture/configuration bags.
- [ ] Test setup follows the inspected reference suite. There are no fixture carrier classes or shared assertion/fixture-loader frameworks.
- [ ] At most one suite-local `Refs` per test records observations; responses come from builder parameters, not `Refs`.
- [ ] Suite order within a file: reusable non-trivial mock data, then use-case tests, then observable `Refs` (only if needed), then `buildX` helpers.
- [ ] Prefer a codec that encodes the wire format directly over a DTO; a DTO exists only when it cannot leak past its boundary and no codec can express the format cleanly.
- [ ] A stub stays in the suite that needs it. Share only non-trivial substitutes or domain data used by multiple suites; the shared helper must own meaningful setup, not merely rename a constructor.
- [ ] Integration tests acquire, use, and release their own resource explicitly through the ecosystem's standard primitive — never through a callback-style setup helper.
- [ ] Architecture rules live in one `ArchitectureTest` with a single test.
- [ ] Use cases are tested through their driving port with deterministic fake driven ports; SQLite and HTTP are tested at their own seams.
- [ ] A port's contract (shape, ordering, validity) is tested at its adapter seam, not re-tested in each use case that consumes it.
