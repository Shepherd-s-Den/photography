# Design principles

How this codebase is designed and built. These are engineering principles, not agent instructions — they apply to anyone working here, human or otherwise.

These principles are language-agnostic; this document grounds them in the choices this repo actually makes.

The aims:

- Low, even cognitive load across the codebase.
- Cheap to extend, and cheap to change on purpose.
- Intentional changes that respect the domain and its code structure.
- Maintenance and dependencies that stay healthy over the long run.
- Reuse and modularity, favoured over one-off solutions.

What we don't chase: gold-plating — polish past the point where it benefits the business or the team.

## 1. Ports & Adapters

The core is isolated from the outside world. Pure types live in the domain; orchestration and business logic live in the application service's implementation class, while companions hold construction and result types only. A business-external dependency is named by an **algebra** — a `trait` expressed in domain terms, not a vendor's — while generic infrastructure any service could need regardless of business logic (time, identity, logging, mutual exclusion) is a **capability**. Concrete **adapters** implement these contracts and live apart from the core, grouped by the external dependency they own. The composition root is the only place that binds concrete adapters to application algebras.

Operation parameters contain only inputs that can change for that call. Inject fixed business collaborators and settings once through `make` and the private constructor, rather than asking callers to supply services or whole traits on each invocation.

The composition root loads and parses configuration into the types the component needs; `domain` and `usecases` receive those values without parsing them.

Because the core depends only on algebras and capabilities, it can be tested by substituting their interpreters. Adapters are proven at their seam against the real boundary they serve, never by mocking the thing they exist to talk to.

Application services own business decisions; persistence adapters own representation validity, basic relational constraints and atomic writes of supplied decisions. Choose coordination for the supported deployment topology and cover the full read/decision/write sequence. A transaction's atomicity and a business decision's concurrency safety are separate guarantees, each proven at its owning boundary.

## 2. Domain-Driven Design (a pragmatic subset)

The domain is modelled explicitly and made hard to misuse:

- **Strong types over primitives.** Identifiers, quantities, and labels are their own types, not bare `String`/`Int`.
- **Invalid states are unrepresentable.** Prefer a type that cannot hold a bad value over a check that might be forgotten; prefer a total `enum` and exhaustive matches over open-ended strings. Avoid nullable/optional fields where a value is always present.
- **Domain values hold no capabilities.** Values carry self-contained, independently testable data and rules only — no clock, database, or I/O. Domain services orchestrate capability ports around those pure values.
- **Give event time an explicit owner.** When an event can be recorded after it happened, its timestamp is caller input, not an implicit server clock read. Convert local entry to an absolute instant at the boundary, validate it, and preserve it as part of the event's history.
- **Use cases own cross-value invariants.** A rule spanning several values of one aggregate (for example, an album's first spread must be its start spread) is enforced where the aggregate is built, in the use case. Value types expose no construction path that can produce an invalid aggregate.
- **Ubiquitous language.** Code, docs, and the UI use agreed domain terms. Consult the maintainer before introducing a new term. Translate external terminology at the boundary (see §4).

## 3. Fractal Design

The system reads the same at every zoom level: `main` is the most zoomed-out view, and you can descend — composition root → service → domain → method — with each level staying small and single- purpose. Concretely:

- **One responsibility per unit**, one reason to change.
- **One abstraction level per method** — don't mix high-level orchestration with low-level detail in the same body. Methods stay short (roughly 7–10 lines). Keep control flow linear and information-dense: name derived values once, make conditions explicit, and avoid expressions whose formatting contributes more lines than meaning.
- **Most-important-first ordering, everywhere.** Public entrypoints come before private helpers; the critical parameters of a function before the incidental ones; the defining fields of a case class before the rest. Someone opening a file should meet its purpose first, not its plumbing. A file that greets the reader with private helpers is ordered backwards.
- **Locality of Behavior over structural deduplication.** Keep behavior beside the state and UI that it governs. Similar-looking code is not sufficient reason to share an abstraction when the use cases may evolve independently.
- **DRY applies to knowledge, not lines.** Centralize a rule, decision, or contract when independent divergence would be a bug. Allow incidental code similarity when the duplicated code represents separate knowledge or responsibilities.
- **Every abstraction must own a real seam.** Extract an interface, component, or helper only when it names a stable responsibility and has real consumers. Do not replace local code with configuration bags, pass-through layers, or generic frameworks that merely hide differences.
- **Represent one fact once.** When one measurement determines a classification, model the valid classifications as one closed type carrying the measurements that apply to each case. Do not expose parallel fields that callers can combine into contradictions. Persisted reference catalogs have one authoritative inventory; tests establish their observable guarantees without maintaining a second complete catalog.
- **Keep mutable records separate from cached projections.** A projection carries identity and derived measurements; read current entity details from their authoritative source and join by identity. A failed read or mismatched identity must not appear as a successful, current view.
- **Layout follows content constraints, not one viewport.** UI components own their structural boxes, use fluid bounds, and define narrow-width behavior. Decorative layers must not stand in for structure that controls layout.

## 4. Anti-Corruption Layer

External models are translated into internal ones at the edge, so no foreign shape or semantics leak into the domain. This boundary absorbs external model changes without forcing changes to business logic.

Keep **wire representations** and **domain models** distinct. Adapters translate between them, and validators reject malformed input before it crosses inward. Driven ports return data already valid for the domain: adapters enforce the port's contract (for example, non-empty lists, allowed kinds, ordering), and use cases and domain types trust it rather than re-checking or handling invalid input.

Domain types declare the strongest constraints available. Parsing, normalization, refinement and representation validation belong to the adapter that owns the source; application services receive typed values. A screen's setup state, selectable catalog or response envelope does not define a domain aggregate.

Boundary encodings must preserve domain semantics used outside the core. If storage compares encoded values, their canonical representation must preserve domain ordering, including across migrated data.

## 5. Indirection Layers

Just as the ACL protects the domain from the outside, interfaces protect internal components from each other. Business collaborators talk through explicit `trait`s; the only things called concretely are pure, stateless utilities.

- **Caller-defined interfaces.** Shape an API around the work its caller needs. Trace every parameter to the information the operation consumes, including factories and private helpers. Accept the smallest meaningful inputs rather than a whole aggregate, configuration or service bundle for a few fields. When several inputs form one cohesive request, define a purpose-specific type containing only those inputs.
- **Bounded dependencies.** A class has at most five constructor-injected dependencies, excluding context-bound generic capabilities. Split responsibilities rather than hiding dependencies in bundles.
- **Contracts expose responsibilities, not implementations.** Injected interfaces make dependencies understandable and substitutable in tests without reopening or redundantly testing collaborator internals. Defining those contracts forces deliberate abstraction and domain modelling.
- **Adapters depend on application contracts, never on other adapters or on infrastructure capabilities directly.** A transport handler calls an application service and, in its own tests, substitutes that service. It must not reach past the service to persistence or another external system; each external interaction is reached through the algebra owned by the service responsible for it.

## 6. Build Guardrails

The build starts deliberately small. Add each compiler, formatting, analysis, and test gate alongside the code or dependency it governs, and document the command that runs it.

- **Compiler feedback stays actionable.** Prefer compiler settings that make unsafe constructs, unused code, and accidental widening visible as errors.
- **Dependencies stay intentional.** Pin toolchains and libraries, automate update proposals where practical, and justify production dependencies by the capability they provide.
- **Tests establish behavior.** Add tests with each observable behavior and exercise the owning boundary. Coverage is a design signal, not a target to game; never weaken a gate to make it pass.
- **Guard architectural boundaries.** As the application grows, encode important dependency-direction rules in tests or build checks rather than relying on review discipline alone.

### Conventions the build does not mechanize

- **Real imports, not fully-qualified paths.** Reach for a symbol by importing it; inline `a.b.c.Thing` references obscure dependencies.
- **Do not contort a test to cover glue.** If a behavior is hard to test, give it a meaningful seam or keep it as clearly identifiable composition code.
- **Errors stay explicit.** Avoid unsafe casts, partial operations, swallowed failures, and success-shaped fallbacks.

## 7. Keeping Complexity in Check

§3 shapes the system at rest; this is how it holds that shape as it grows. Complexity must earn its place through the value it delivers; narrow or reject a change when that tradeoff does not hold.

- **Occam's Razor.** Compare alternatives that fit the system; prefer fewer assumptions and moving parts.
- **Compatibility follows deployment needs.** Establish which deployed versions or retained data require preservation before adding upgrade paths. For undeployed software, prefer correcting the initial design and explicitly targeting disposable development data over building speculative compatibility machinery.
- **Scope versus complexity.** Ask whether 90% of the value is achievable with 10% of the complexity.
- **Intentional change.** Before the spec, decide what to extend, reuse, build, or split; record why, and revisit the decision as evidence changes.
- **Refactor as work arrives.** Extract newly exposed responsibilities into their own modules in the change that exposes them, not a later cleanup. Leave no broken windows behind.
- **Size is measured, not felt.** A unit past a screenful, a test suite past roughly 3× the code it exercises, or code that is difficult to test warrants inspection, not an automatic split. Split distinct responsibilities and justify larger cohesive units; never trim the suite to hide the ratio.
- **Complexity outranks completeness.** When holding complexity down conflicts with shipping more, reduce complexity first and ship the fuller feature second.

## 8. Code Expresses Intent

Meaningful names, strong types (§2), clear constructs, and cohesive modules (§3) carry intent. Comments are often design smells: investigate the use case that needs explaining before adding one.

- **Design before commentary.** Question the API, domain model, and responsibility boundaries; rewrite the code to remove the need for explanation when possible. A newly identified subdomain may need its own package.
- **High-signal documentation.** Keep rationale in concise, authoritative docs. Retain comments only for constraints or non-obvious reasons code cannot express, such as a library workaround or deliberate deviation.
- **Avoid duplicated narration.** No scaladoc/JSDoc restating a type, method, or field; no notes about code that used to be here or once didn't compile (git holds the history). Contract-level text that ships to consumers is API content, not a code comment, and stays.

## Typelevel and Tagless Final

The backend uses Scala 3 and the Typelevel ecosystem, with Tagless Final algebras for effectful application boundaries. Choose concrete effect runtimes at the composition root.

Request capabilities such as `Clock`, `IdGenerator` and `Logger` through `F` context bounds. Use them through their typeclass accessors, not explicit parameters to methods, `make` or private constructors.
