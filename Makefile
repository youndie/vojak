# Two gates, and CI runs exactly these targets.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal state
# of affairs, and then neither is read. So: whatever is not behind one of these targets is not a
# gate, and whatever is runs the same way in both places.
#
# `check` reads the documents — python, seconds, no JDK; on the Mac it runs as `LOCAL=1 make check`.
# `build` compiles and tests — a toolchain, Docker and minutes; it runs on the Linux box through
# `~/.claude/bin/wsl-run make build`, and in CI.
#
# NOT here: `docs_check.py --on-main`, which makes `status: draft` an error on the default branch.
# It runs in CI on push to main only, because it is branch-specific: a draft is legal in a pull
# request, where it means "this branch will make it true", and a defect the moment it merges.

DOCS ?= docs
BACKLOG ?= backlog.md
REPOS ?= ..
PY ?= python3
GRADLE ?= ./gradlew
# CI passes `--no-daemon`; a workstation wants the daemon. The flags are the only difference.
GRADLEFLAGS ?=
# The test database. Fixed port and name so that a second `make build` reuses it, and so that
# `docker stop vojak-test-pg` is the one command that shows the suite really needs it.
TEST_PG ?= vojak-test-pg
TEST_PG_IMAGE ?= postgres:18

.PHONY: check gate report fix build postgres help

help:
	@echo "make check   - the documentation gate: blocking, exactly what CI's check job runs"
	@echo "make build   - the code gate: Postgres in Docker, then gradle build; exactly what CI's build job runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - regenerate the backlog index, fill in missing coverage-map lines"

check: gate report

gate:
	$(PY) scripts/backlog_index.py --check --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking, on purpose: every scenario is *target* until the code exists, and every anchor in a
# draft document points at a file that does not exist yet.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/backlog_index.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)

# The code gate. `build` runs jvmTest and linuxX64Test; linuxArm64 is compiled and its tests are not
# run — Kotlin/Native has no arm64 test task on an x86-64 host.
build: postgres
	$(GRADLE) build $(GRADLEFLAGS)

# Starts the test database, or reuses the one already running; waits until it answers.
postgres:
	@docker start $(TEST_PG) >/dev/null 2>&1 || \
	  docker run -d --name $(TEST_PG) -p 127.0.0.1:55440:5432 -e POSTGRES_PASSWORD=vojak $(TEST_PG_IMAGE) >/dev/null
	@for i in $$(seq 60); do docker exec $(TEST_PG) pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1 && exit 0; sleep 1; done; \
	  echo "$(TEST_PG) did not become ready in 60 s"; exit 1
