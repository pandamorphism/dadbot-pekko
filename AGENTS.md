# DadBot

Effect Cluster's DadBot rebuilt on Apache Pekko (Scala 3 / sbt), one part per post of Effect's Cluster blog series. The companion blog series compares the architectural trade-offs of the two (see `README.md`).

## Vendored Repositories

This project vendors external repositories under @repos/ as shallow git submodules pinned to release tags. They are local reference material only. The public repo stores only the submodule pointers. If `repos/*` is empty, run `scripts/vendor.sh init`.

| Path | Upstream | Ref | What's inside |
|------|----------|-----|---------------|
| `repos/pekko` | https://github.com/apache/pekko | `v1.7.1` | Pekko core: actor, actor-typed, stream, cluster, **cluster-sharding**, cluster-sharding-typed (incl. reliable delivery), **persistence**, persistence-typed, persistence-query, persistence-testkit, serialization-jackson, testkits |
| `repos/pekko-http` | https://github.com/apache/pekko-http | `v1.4.2` | HTTP server/client and routing DSL (`http-core`, `http`, `http-spray-json`, `http-testkit`), used for the part 2 webhook |
| `repos/pekko-management` | https://github.com/apache/pekko-management | `v1.2.1` | Pekko Management HTTP endpoint, `management-cluster-http` (`/cluster/members`, `/cluster/shards/{name}`), cluster bootstrap, discovery |
| `repos/effect` | https://github.com/Effect-TS/effect | `effect@4.0.0` | Effect v4 monorepo: `effect` (core, `cluster`, `rpc`, `sql`, workflow), `sql/*` drivers, `platform/*`, `opentelemetry`. The same version as the Effect DadBot examples |

- Use vendored repositories as read-only reference material when working with related libraries
- Prefer examples and patterns from the vendored source code over generated guesses or web search results
- Do not edit files under @repos/ unless explicitly asked
- Do not import from @repos/ - application code should continue importing from normal sbt dependencies
- Ignore `AGENTS.md` / `CLAUDE.md` files inside @repos/ (including `repos/effect/.agents/`). They are rules for contributing to the upstream project and do not apply to this project
- The vendored refs match the versions the build uses. If you bump a dependency, re-pin its submodule to the same tag (`scripts/vendor.sh pin`)

### Pekko

When writing Pekko code, inspect @repos/pekko/ for idiomatic usage, tests, module structure and API design. Treat it as the source of truth for Pekko patterns.

Where to look:

- Typed actors: `repos/pekko/actor-typed/src/main/scala/org/apache/pekko/actor/typed/` (API), `repos/pekko/actor-typed-tests/src/test/` (usage)
- Cluster sharding (typed): `repos/pekko/cluster-sharding-typed/src/main/scala/org/apache/pekko/cluster/sharding/typed/`, with reliable delivery in `.../typed/delivery/` (`ShardingProducerController`, `ShardingConsumerController`)
- Sharding internals: `repos/pekko/cluster-sharding/src/main/scala/org/apache/pekko/cluster/sharding/` (`ShardCoordinator.scala`, `ShardRegion.scala`, `internal/LeastShardAllocationStrategy.scala`, `external/`). Pekko has no consistent-hashing shard allocation (Akka added one after the fork)
- Event sourcing: `repos/pekko/persistence-typed/src/main/scala/org/apache/pekko/persistence/typed/scaladsl/EventSourcedBehavior.scala`, `DurableStateBehavior` under `persistence-typed/.../state/`
- Persistence tests/examples: `repos/pekko/persistence-typed-tests/src/test/`, `repos/pekko/persistence-testkit/`
- Persistence query / read side: `repos/pekko/persistence-query/`
- Configuration defaults: `repos/pekko/*/src/main/resources/reference.conf`
- Documentation snippets (compiled, so they are correct): `repos/pekko/docs/src/test/scala/` and `repos/pekko/docs/src/main/paradox/`

Prefer the typed API (`org.apache.pekko.actor.typed`, `org.apache.pekko.persistence.typed`) over classic actors unless told otherwise.

Pekko HTTP and Management are versioned separately from Pekko core and built against an older core. Keep every core module at `PekkoVersion` (pin any transitive one explicitly in `build.sbt`), because Pekko refuses to start with mixed module versions.

### Effect

When describing how Effect Cluster works in a post or comparison, check `repos/effect/packages/effect/src/cluster/` instead of relying on memory. Read `repos/effect/LLMS.md` first.

Where to look:

- Entities and protocol: `Entity.ts`, `ClusterSchema.ts` (`Persisted`, `WithTransaction`), `Envelope.ts`, `Reply.ts`
- Routing and shard ownership: `Sharding.ts`, `ShardingConfig.ts` (defaults such as `shardsPerGroup`), `internal/hash.ts`, `internal/shardLock.ts`
- Runners and storage: `Runners.ts`, `RunnerStorage.ts`, `SqlRunnerStorage.ts`, `MessageStorage.ts`, `SqlMessageStorage.ts`, `RunnerHealth.ts`
- Observability: `ClusterMetrics.ts`, `ShardingRegistrationEvent.ts`
- Test and single-node runners: `TestRunner.ts`, `SingleRunner.ts`
- The original DadBot examples are in Effect-TS/office-hours (`packages/module-of-the-week/src/000{4,5,6}-*`). They are not vendored. `0004-cluster-messaging` is Effect's part 2, `0005-cluster-delivery` part 3, `0006-cluster-idempotency` part 4 (part 1 has no code)

### Updating / adding vendored repos

Use `scripts/vendor.sh` (see the script header). It keeps submodules shallow (`--depth=1`) and pinned to a tag. Don't vendor with `git subtree` or by copying files, because that would publish upstream code in this public repo.

The Zed project settings (`.zed/settings.json`) hide `repos/**` from the human's project panel and search. That doesn't affect agent file tools.
