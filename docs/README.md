# docs — vojak

Leader election and distributed locks for Kotlin/Native and JVM services, on Postgres advisory
locks. The documentation is layered; links run top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (behaviour + BDD) ]
                     │
[ Service (a published module: ownership, dependencies, release) ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the artefacts each fact names |
| Feature | `features/` | *what* the library does and *why*; BDD scenarios | this repository |
| Service | `services/` | what each published module owns, depends on and ships | this repository + the module's code |

There is **no `screens/` layer** — a library has no client — and **no `api/` layer**: vojak has no
HTTP surface. Its public surface is Kotlin types, described in the service documents and anchored
to the source files.

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items themselves are
one file each in [`backlog/`](backlog/), cited as `[B-04](backlog/B-04-with-lock.md)`.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. A feature spanning two modules is **one** file with two entries in
  `involved_services`.
- BDD scenarios are written against the real behaviour: until the code exists they are marked
  *target*, and they are verified against the code before a document goes `active`.
- **The primary consumer is a coding agent.** Every document carries code anchors — paths to the
  source file or directory — so that the reader reaches the code in one hop. Do not copy what lives
  in code (signatures, SQL, config keys); give the path.
- Language: English. Code identifiers and SQL verbatim as in the code.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.

## Checks

```bash
pip install pyyaml
make check      # the gate: backlog index, links and anchors present, coverage map; then the reports
make fix        # regenerate the backlog index, append missing coverage-map lines
```

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a
person — the machine only guards the membership.

### Research (1)

- [x] [research-architecture](research/research-architecture.md) — what advisory locks and sqlx4k actually do, why the lock lives in an open transaction, the hypotheses B-01 measures
