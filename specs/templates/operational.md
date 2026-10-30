# <Component> operations

> Standard: Agentic Engineering Standards v1.2.0
>
> Living-doc template. Apply the [living-doc quality policy](README.md). Describe how to run and support the current service; pipeline operation belongs in `ci/specs/`.

## Run locally

<!-- Supported startup/shutdown path, prerequisites pointer, isolated state and how to locate
the running service. Link to configuration rather than copying all settings. -->

## Deployment and runtime dependencies

<!-- Actual deployment target, supported instance topology, network exposure and persistent
state ownership. For NAS deployment, explain read-only originals versus writable application
data. Give a runnable entrypoint when supported; identify missing packaging honestly. -->

## Observability

<!-- Available health checks, logs and metric/alert surfaces; explain what they establish and
where operators find them. Link to monitoring configuration when present. Distinguish operating
recommendations from implemented telemetry; state an important monitoring gap briefly. -->

## Recovery and maintenance

<!-- Backup/restore consistency, restart behavior and safe recovery boundaries. Explain what an
operator must preserve and when intervention is needed, not internal recovery algorithms. -->

## Scaling characteristics

<!-- Optional. What drives load and which supported deployment limits affect operation. -->
