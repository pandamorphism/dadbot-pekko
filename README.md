# DadBot on Pekko

Effect's [Cluster series](https://effect.website/blog/module-of-the-week/cluster-actors) builds **DadBot**. Each chat gets its own `Dad` entity. Dad waits for the chat to go quiet, then tells one pun about what was said, and he never tells the same joke twice in a chat.

This repository builds the same app on [Apache Pekko](https://pekko.apache.org/) (Scala 3, Cluster Sharding), one part at a time. It is the companion code for a blog series about the architectural trade-offs of building event-driven systems with cluster sharding, comparing Effect and Pekko.

## Parts

| Part | Effect post | Pekko module | Status |
|------|-------------|--------------|--------|
| 1. Actors | [Cluster, Part 1](https://effect.website/blog/module-of-the-week/cluster-actors) | `part1-actors` | code done, post in progress |
| 2. Messaging | [Cluster, Part 2](https://effect.website/blog/module-of-the-week/cluster-messaging) | `part2-messaging` | planned |
| 3. Delivery | [Cluster, Part 3](https://effect.website/blog/module-of-the-week/cluster-delivery) | `part3-delivery` | planned |
| 4. Idempotency | [Cluster, Part 4](https://effect.website/blog/module-of-the-week/cluster-idempotency) | `part4-idempotency` | planned |

The Effect source for each part is in [Effect-TS/office-hours](https://github.com/Effect-TS/office-hours/tree/main/packages/module-of-the-week/src).

## Running

You need JDK 17+ and [sbt](https://www.scala-sbt.org/). Each part has a `Program` that plays the conversation from Effect's `Program.ts`:

```bash
sbt test               # all parts
sbt part1-actors/run   # naive handler repeats a joke; sharded Dads don't
```

`common` holds what every part shares: `PunGenerator` (the same jokes as Effect's), `Chat` and the scripted conversation.

## Vendored sources

`repos/` holds upstream repositories as shallow git submodules pinned to release tags. This repo stores only the pointers, not the code:

| Path | Upstream | Ref |
|------|----------|-----|
| `repos/pekko` | [apache/pekko](https://github.com/apache/pekko) | `v1.7.1` |
| `repos/effect` | [Effect-TS/effect](https://github.com/Effect-TS/effect) | `effect@4.0.0` |

They are only read-only reference material for coding agents. The idea comes from [The One Weird Git Trick That Makes Coding Agents More Effect-ive](https://effect.website/blog/the-one-weird-git-trick-that-makes-coding-agents-more-effect-ive), which uses `git subtree`. Submodules are used here instead so that a public repo doesn't republish other projects' code. The application code depends on the published artifacts, not on these files. You only need them if you work on this repo with a coding agent:

```bash
scripts/vendor.sh init                 # fetch pinned sources (shallow)
scripts/vendor.sh pin pekko v1.7.2     # move a pin, then commit
scripts/vendor.sh add pekko-management https://github.com/apache/pekko-management.git v1.2.1
```

## License

[Apache-2.0](LICENSE). The repositories referenced as submodules are under their own licenses.
