package com.phoneai.app

/**
 * Built-in engineering playbooks, loaded on demand through `devspace {action:"playbook", name}` so they cost nothing
 * until used. Adapted, with credit, from the MIT-licensed Hermes Agent skills (systematic-debugging,
 * test-driven-development, requesting-code-review, codebase-inspection, github) and extended with an end-to-end
 * platform-build playbook that ships web apps, software and APKs through GitHub CI.
 */
internal object OrionPlaybooks {
    private val playbooks: Map<String, Pair<String, String>> = linkedMapOf(
        "build-platform" to ("Build a full webapp, software or APK end to end and ship it through GitHub CI." to """
BUILD A PLATFORM — plan, scaffold, build in slices, test, ship.
1. SPEC: restate the goal in 3-6 lines: users, core flows, data, platform (web | cli/software | android). Write it to /workspace/<project>/PLAN.md with a checklist of slices. Ask the owner only about choices that change the outcome.
2. SCAFFOLD: devspace new_project {name, kind: web|api|node|python|cli|android}. For fullstack, make an api project and a web project and have the web call the api. Run devspace setup if a toolchain is missing.
3. SLICES: build one vertical slice at a time (smallest thing that works end to end), never the whole tree at once. After each slice: run it, read the real output, tick the PLAN.md box, commit with a conventional message (feat:, fix:, test:, chore:).
4. TEST: write the test first for logic (see playbook tdd). Web: service_start the server, then service_health and fetch the page; use the browser tools to click through the main flow. APK: you cannot compile locally, so push and let CI build.
5. SHIP: git remote + push a branch, open a PR with gh/REST, wait for CI, read failures from check-run annotations (see playbook github-flow), fix the root cause, repeat until green, then merge. APK: download the debug APK artifact from the workflow run and hand the owner the link.
6. REPORT: say what exists, where it lives, how to run it, what was verified and what was not. Keep code in files; chat gets plain words.
Rules: never claim something works until you ran it; one failing thing at a time; checkpoint with git so every step is reversible; prefer boring, standard tools (pytest, npm test, gradle) over inventing."""),
        "debugging" to ("Find the root cause before fixing anything." to """
SYSTEMATIC DEBUGGING (adapted from Hermes / obra superpowers). Iron law: no fix without a root cause.
1. LOOP FIRST: build a tight command that goes red on the exact symptom and green when fixed (a test, a curl, a script). No loop, no theory.
2. REPRODUCE and read the full error, stack trace and the last change that touched it. Check recent commits.
3. TRACE BACKWARDS from the bad value to where it first went wrong; in multi-part systems log at each boundary to find which layer breaks.
4. HYPOTHESIS: state one cause, change one thing, run the loop. Wrong? Revert and form the next. Never stack guesses.
5. FIX the cause (and its siblings), add a regression test, rerun the whole suite.
6. If three fixes fail, stop: the design assumption is wrong. Re-read, or ask the owner.
Red flags: "quick fix for now", changing several things at once, fixing the symptom, skipping the repro."""),
        "tdd" to ("Write the failing test first, then the smallest code to pass." to """
TEST-DRIVEN DEVELOPMENT (adapted from Hermes). No production code without a failing test first.
RED: write one small test for one behaviour; run it; watch it fail for the right reason.
GREEN: write the least code that passes; run all tests.
REFACTOR: clean up with tests green; do not add behaviour.
Repeat per behaviour. Test behaviour, not implementation; one assertion idea per test; real code over mocks where cheap.
Bug fix = reproduce in a failing test first. If you wrote code before the test, delete it and start from the test.
Tools: python -m pytest -x -q, npm test, gradle testDebugUnitTest (via CI)."""),
        "review" to ("Review a change before it merges." to """
CODE REVIEW (adapted from Hermes requesting-code-review). Before merging your own work:
1. Read the whole diff (git diff main...HEAD) as a stranger would.
2. Check: does it do what was asked and nothing more? edge cases and error paths? secrets or keys committed? new dependencies justified? tests cover the change? names and comments honest?
3. Run tests and the linter. Confirm the claimed behaviour by running it.
4. List findings by severity (blocker, should-fix, nit). Fix blockers and should-fix, then re-read the diff.
5. Summarise in the PR: what changed, why, how verified, known gaps."""),
        "github-flow" to ("Branch, commit, PR, read CI failures, merge." to """
GITHUB FLOW (adapted from Hermes github skill). Use the REST API (gh api) when GraphQL is blocked.
BRANCH: git checkout -b feat/<topic> from the default branch. COMMIT small, conventional (feat|fix|docs|test|chore: summary).
PUSH: git push -u origin <branch>. PR: gh api repos/OWNER/REPO/pulls -f title=... -f head=<branch> -f base=main -f body=...
CI: poll gh api repos/OWNER/REPO/commits/<sha>/check-runs. On red: read gh run view <id> --log-failed or check-run annotations; find the first real error, fix the root cause, push. A job gets one re-run only for clear network flakes (e.g. Maven 429); never skip or delete a failing test.
MERGE only when CI is green: gh api -X PUT repos/OWNER/REPO/pulls/N/merge -f merge_method=squash. After a squash merge the local branch looks unpushed; that is expected.
APK: workflow android.yml builds app-debug.apk and uploads it as an artifact; fetch it from the run's artifacts."""),
        "inspect" to ("Understand an unfamiliar codebase quickly." to """
CODEBASE INSPECTION (adapted from Hermes). 1) devspace project_view for the tree and git log. 2) Read README and the manifest (package.json, pyproject.toml, build.gradle). 3) Find entry points and the module boundaries; grep for the thing you must change and its callers. 4) Run the existing tests to learn the baseline. 5) Write a 5-line map (entry, core, data, tests, how to run) before editing anything.""")
    )

    fun names(): List<String> = playbooks.keys.toList()
    fun body(name: String): String? = playbooks[name.trim().lowercase()]?.second?.trim()

    fun index(): String = "playbooks (devspace {action:\"playbook\", name}): " + playbooks.entries.joinToString("; ") { "${it.key} — ${it.value.first}" }

    fun run(name: String): String = if (name.isBlank()) index() else body(name) ?: "error: no playbook '$name'. " + index()
}
