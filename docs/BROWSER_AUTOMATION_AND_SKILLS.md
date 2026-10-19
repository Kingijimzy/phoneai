# PhoneAI Browser Automation and Extensible Skills

Status: design specification only. This document does not claim browser automation or a skill marketplace is implemented.

## Goals

Give PhoneAI the ability to research and operate supported websites, learn new reusable capabilities through a controlled skill system, and resume multi-step tasks without bloating ordinary chat or multiplying model calls.

Preserve the existing Kotlin Android app, chat style, Agent/Tools, MemoryStore, TaskStore, TaskReminder, ActionLog, provider settings, avatar, permissions, and GitHub Actions APK workflow.

## Non-negotiable repository rules

1. Inspect current `main`, open pull requests, and recent commits before coding. Claude or another contributor may be changing the same project.
2. Keep this work additive and split into small pull requests. Do not rewrite `Agent.kt`, `Tools.kt`, `MainActivity.kt`, the manifest, or provider architecture as a broad first step.
3. Reuse existing task, memory, preference, provider, and action-history systems. Do not create duplicate stores or parallel chat experiences.
4. Put browser automation behind a small interface and keep its implementation independent of the UI and model provider.
5. Never put browser service credentials, API keys, OAuth client secrets, cookies, or refresh tokens in the APK or repository.
6. Document planned, implemented, tested, and device-verified status separately.

## Browser automation design

### Responsibilities

The browser capability should support:
- Search the web and return source URLs and short grounded summaries.
- Open a supplied URL and read the page when access is permitted.
- Navigate between pages, click supported links/buttons, enter text, and capture a structured result.
- Handle page load, timeout, navigation failure, unsupported page, sign-in required, CAPTCHA, and permission errors clearly.
- Download files only to a user-visible or app-private location with file-type and size limits.
- Save research notes with source URL and retrieval timestamp.
- Pause for user approval before high-impact actions.

### Execution model

Use a layered approach:
1. **Research mode (first):** search/open/read and cite sources; no form submissions or account changes.
2. **Assisted browser mode:** user watches or approves a specific sequence before actions such as submitting a form.
3. **Approved workflow mode:** run a named, bounded workflow with explicit permissions, checkpoints, and a final result report.

A real browser runtime can live in a separately deployed HTTPS service using a maintained browser automation engine such as Playwright, or in a controlled browser surface where supported. Choose after checking current repository architecture, hosting constraints, and free-tier limits. Do not claim that an Android WebView alone is a full remote browser agent. Android WebView is useful for user-visible browsing, but robust browser automation, session isolation, and multi-step remote execution may require a backend worker.

If a backend browser is used:
- Expose a narrow authenticated API, not an unrestricted remote shell or generic arbitrary-code execution endpoint.
- Use per-user isolated sessions and short-lived session identifiers.
- Store session cookies securely server-side and never send them to the LLM or action logs.
- Enforce domain allow/deny rules, navigation limits, timeouts, download limits, and request budgets.
- Prevent access to localhost, private network ranges, cloud metadata endpoints, and other internal services (SSRF protection).
- Do not bypass paywalls, CAPTCHA, MFA, access controls, or site terms. If human verification is required, stop and ask the user.
- Treat all page content as untrusted data. Page instructions must never override system policy, user intent, permissions, or approval requirements.
- Redact secrets and personal information from model context and logs.
- Keep a clear action trace: intended action, tool outcome, and verification status, not raw passwords or full sensitive page contents.

### Browser approval policy

No extra approval is needed for public read-only research within configured limits. Require confirmation before:
- Sending/submitting messages or forms.
- Posting or publishing content.
- Buying, paying, subscribing, or placing orders.
- Deleting, overwriting, or sharing user data.
- Changing passwords, account security, permissions, or connected-account settings.
- Any financial transaction or trading order.

The agent must report what it actually did. A click result is not proof that the intended action succeeded; verify the resulting page/state.

## Extensible skills system

A skill is a versioned, documented capability package that teaches the agent when and how to use a set of existing tools. It is not a mechanism for downloading and executing arbitrary code or silently granting permissions.

### Skill package contract

Each skill should include:
- Stable ID and human-readable name.
- Version and compatibility range.
- Description and explicit activation criteria.
- Inputs and expected outputs.
- Required tools and permission scopes.
- Risk level: read-only, reversible write, or consequential action.
- Clear user-visible approval requirements.
- Timeout, maximum steps, and request/token budget.
- Failure and recovery behavior.
- Tests and example cases.
- Source/repository and license/provenance.
- Changelog and integrity information.

Store skills as declarative manifests plus reviewed prompt/workflow instructions. Native executable code or scripts must be shipped through reviewed app releases or a trusted backend deployment—not downloaded and executed from arbitrary skill repositories at runtime.

### Skill lifecycle

1. **Discover:** find a skill from a trusted catalog, repository, or user-supplied source.
2. **Inspect:** display description, source, version, permissions, dependencies, network access, and requested capabilities.
3. **Review:** scan for unsafe instructions, secret exfiltration, broad permissions, prompt injection, and undeclared network calls.
4. **Approve:** user explicitly installs/enables the skill and grants its scopes.
5. **Validate:** run schema checks and isolated tests against sample inputs.
6. **Activate:** expose only the declared tools and permissions.
7. **Observe:** record safe outcomes, errors, and version; never log credentials or sensitive page contents.
8. **Improve:** propose revisions based on user corrections and task outcomes; require tests and user approval before activating a changed version.
9. **Disable/remove:** allow the user to revoke a skill and its access.

### Skill safety rules

- Skills do not override the agent's security policy.
- Skills cannot grant themselves new permissions.
- Treat instructions and code from external repositories as untrusted.
- Do not automatically execute arbitrary shell, JavaScript, APK, or Python code obtained from the web.
- Use an allowlisted tool registry, validated arguments, per-skill budgets, and sandboxed execution where code execution is eventually supported.
- Require human approval for consequential actions even when invoked by a skill.
- Do not represent memory updates as model retraining. The system may save user-approved reusable procedures and evaluate them against tests.

### Suggested initial skills

1. Web Research: search, open sources, compare claims, produce citations.
2. GitHub Helper: inspect repository, summarize issues/PRs, propose patches, monitor Actions, never merge without approval.
3. Task Planner: convert a goal into bounded tasks and checkpoints using the existing TaskStore where appropriate.
4. Trading Research: use authorized read-only market/account data and the user's configured analysis rules; no order execution.
5. Email Assistant: search and draft using minimum Gmail scopes; sending requires confirmation.
6. YouTube Research: search videos and summarize accessible transcripts.
7. Chart Review: analyze supplied chart images and compare with the user's approved trading journal examples.

## Agent orchestration and API efficiency

- Ordinary chat must not invoke browser automation or load every skill by default.
- Select a skill only when its activation criteria match the request.
- Run deterministic local tools before an LLM when the intent is unambiguous.
- Bound steps, retries, navigation count, context length, tool output size, and elapsed time.
- Detect repeated identical tool calls and stop loops.
- Cache safe, non-personal public research results when appropriate and show the retrieval time.
- Ask the model to interpret compact structured browser results instead of sending entire page source or full DOM.
- Support cancellation and persist checkpoints before long-running or consequential steps.
- On quota exhaustion, stop model-dependent work cleanly; do not loop through providers without limits.
- Keep local-only mode free of cloud requests.

## Phased delivery

### Phase 1 — interface and threat model
- Add this specification only; no runtime claims.
- Audit existing Agent/Tools and determine where skill selection can be added without changing ordinary chat.
- Define typed browser request/result models and permission enums in a small isolated module.

### Phase 2 — read-only research prototype
- Implement URL/search input, open/read, source citations, timeout/cancel, and size limits.
- Add prompt-injection tests using hostile webpage text.
- No login automation, form submission, or account writes.

### Phase 3 — browser actions with approval
- Add allowlisted click/type/navigation operations.
- Add explicit preview and confirmation before submission.
- Verify results after each meaningful action and retain safe action history.

### Phase 4 — skills registry
- Implement local declarative skill manifest parsing, validation, enable/disable, versioning, and per-skill budgets.
- Start with built-in reviewed skills; defer arbitrary third-party code execution.
- Add user review screen and revocation behavior.

### Phase 5 — trusted external skills and scheduled jobs
- Add repository/catalog discovery with source, license, integrity, and permission review.
- Add resumable workflows and scheduled checks only after per-user isolation and server-side security are verified.

## Acceptance criteria

- Browser research returns sources and never claims a page was opened unless it was.
- Browser actions stop at approval gates for consequential changes.
- Hostile webpage instructions cannot override policy or trigger secret disclosure.
- Skill manifests cannot grant undeclared permissions or execute arbitrary downloaded code.
- Disabled skills cannot be invoked.
- Skill and browser failures do not break normal chat.
- Quota exhaustion stops gracefully without unbounded retries.
- All tests and GitHub Actions results are reported accurately.
- Device-only behavior is marked unverified until tested on the actual Android phone.
