# Orion UI/UX Reset

Status: implementation plan and repository audit, 2026-11-13  
Base: `orion-reliability-release-candidate` at `ea7f4de847805b876234dbe14d87ee6683df94b2`

## Product objective

Make Orion feel like a coherent, professional assistant platform rather than a collection of Android screens. Chat is the primary workspace. Normal conversation, execution, terminal sessions, code, research, files, generated documents, and history must have distinct but consistent presentations.

The visual direction stays graphite / silver: near-black canvas, restrained graphite surfaces, clear typography, consistent spacing, subtle borders, meaningful status colors only, and restrained motion. Avoid decorative pills around every item, tiny all-caps labels, nested cards, and inconsistent page-specific styling.

## Audit findings

- The primary chat is Compose-based, but archived history and transcript preview still use a separate native `Dialog` / `TextView` implementation in `MainChatActivity`. Archived messages therefore lose the main chat's Markdown and specialized message rendering.
- `OrionChatLine` currently stores only `id`, `kind`, `text`, and an optional image URI. Message rendering is inferred from string prefixes and keyword patterns.
- `OrionConversationRepository` persists transcript rows as role/content only. It has a durable `chat_events` table, but a first-class attachment/artifact relationship is absent from the transcript schema.
- The Compose timeline intentionally collapses lifecycle notes into one task card. That is a good anti-spam direction, but the current card is dense, typography is too small, and terminal evidence is a special case rather than a reusable output block.
- The current Markdown renderer handles headings, lists, inline emphasis, links, and fenced code in a lightweight way. It does not yet provide the complete block-level presentation needed for polished code, terminal, tables, quotes, citations, and document artifacts.
- Text documents are extracted and their contents are inserted into the model prompt. The visible transcript does not preserve a durable file attachment object. Other non-image files are copied to the workspace, but the chat still lacks a first-class artifact card with reliable provenance and open/share behavior.
- Image evidence is stored separately and can be restored only under its permission rules. The same general attachment model does not yet exist for documents and generated outputs.

## Target architecture

### 1. Typed conversation content

Replace UI inference from marker strings with a versioned message-content model. Preserve backward compatibility by reading legacy role/content rows as plain-text messages and migrating incrementally.

Initial block types:
- `text`: Markdown source rendered by the shared Markdown renderer.
- `code`: language, source, optional filename, copy action.
- `terminal`: command, backend/session label, stdout/stderr, exit code, duration, running/failed/completed state.
- `activity`: one compact task stream with expandable, timestamped steps and typed evidence.
- `research`: concise findings and source links.
- `attachment`: durable attachment ID and display metadata.
- `artifact`: generated file ID, filename, MIME type, size, checksum, storage reference, preview capability, and explicit open/share actions.
- `image` / `diagram`: existing image and diagram presentation, connected to stable message IDs.

The model/provider prompt must never be used as the visible transcript. Keep the user-visible question separate from extracted document text and internal tool payloads.

### 2. Durable attachment and artifact storage

Introduce a conversation-linked file repository. Copy user-selected files into app-private, quota-controlled storage while retaining safe metadata and source provenance. Persist:
- attachment/artifact ID and owning session/message ID;
- original display name and MIME type;
- byte size and SHA-256;
- app-private relative storage key;
- created timestamp and origin (user attachment, generated output, export);
- extraction/preview status and errors.

Do not persist transient content URIs as the only copy of a document. Never render a fake download/open action. If the file bytes are not available, show metadata and an honest unavailable state. Enforce size limits, safe filenames, path containment, and explicit user actions for export/share/delete.

PDFs should appear as PDF/document cards even when text extraction is used for model context. Extraction is an internal analysis step, not the message presentation.

### 3. One renderer across chat and history

Build shared Compose components and use them in both the live conversation and archived transcript:
- `OrionMessageRenderer` for typed blocks and legacy Markdown;
- `OrionCodeBlock` with language label, horizontal scroll, copy, and optional line numbers;
- `OrionTerminalBlock` with command, backend, live/final status, stdout/stderr, exit code, elapsed time, expand/copy;
- `OrionActivityCard` with one card per objective/task, readable step list, evidence details, and truthful status;
- `OrionFileCard` / `OrionArtifactCard` with icon/thumbnail, filename, type, size, and only supported actions;
- `OrionResearchCard` with source attribution and open-link actions.

Archived history must render the same message components as live chat, not a second implementation made of raw TextViews. History search, rename, delete, restore, and continue-chat actions must remain intact.

### 4. Durable activity replay

The SQLite event ledger remains the source of truth. Add a stable typed event-to-presentation mapper and restore/replay the session's events when opening a conversation. Keep one compact task stream per task in chat; expand it to inspect individual steps and receipts. The Tasks surface can show the complete cross-session history.

Do not fabricate progress from a tool invocation. Distinguish queued, running, awaiting approval, completed, failed, interrupted, and cancelled. Persist terminal evidence in typed fields rather than parsing arbitrary summary strings.

### 5. Visual system

Use `OrionUiPalette` and `Theme` as the single token source. Define reusable typography, spacing, shape, elevation/border, and component tokens. Standardize:
- page header and navigation;
- conversation header and composer;
- message spacing and readable content width;
- section heading, body, caption, code and metadata styles;
- status labels and progress;
- empty, loading, error, and unavailable states;
- cards, menus, dialogs, history rows and settings surfaces.

The main chat should remain the primary workspace. Secondary features should not compete with the transcript. Use one status accent only when it communicates a real state. Avoid excessive chips/pills and nested containers.

## Delivery sequence

1. **Foundation and persistence contract** — define typed message/attachment/artifact records, additive SQLite migration, backward-compatible reads, and migration tests.
2. **Shared renderer** — rich Markdown blocks, code and terminal components, activity card, attachment/artifact cards, and renderer tests.
3. **File lifecycle** — durable imports, extraction without transcript pollution, generated artifact registration, preview/open/share, and restart tests.
4. **History parity** — replace legacy transcript/history rendering with shared Compose components; verify restore, search, rename, delete and continue.
5. **Visual consistency pass** — apply shared tokens to chat, history, tasks, terminal, settings, and secondary pages; remove duplicated legacy styling only after parity.
6. **Acceptance and device review** — run unit tests, Android build, migration tests, screenshot/layout checks, process-death/relaunch tests, and POCO F3 interaction tests.

## Acceptance criteria

- A chat survives Activity recreation, app process death, force-close and relaunch.
- A user attachment remains visible as a file card after relaunch; the original extracted text is not dumped into the visible transcript.
- A generated PDF/document appears as an artifact card with a real file reference and working open/share actions.
- Code fences render in a distinct monospace code block with copy and horizontal scrolling.
- Shell execution renders as a terminal block with command, output, exit code and status; it does not fall back to an ordinary paragraph.
- A task produces one compact activity card, not a flood of duplicate progress cards; its detailed evidence is restored after relaunch.
- Archived history uses the same renderer and typography as live chat.
- Search, rename, delete, restore and continue-chat behavior remain functional.
- All major screens use the same graphite/silver tokens, typography scale, spacing and card treatment.
- No UI action claims an artifact, preview, or terminal session exists unless the backing object/session is actually available.

## Explicit non-goals

- Do not copy Grok or ChatGPT branding or layouts pixel-for-pixel.
- Do not add visual decoration to compensate for missing functionality.
- Do not change tool execution semantics or approval policy as part of a styling-only commit.
- Do not delete legacy history or user files during schema migration.
- Do not claim physical-device acceptance based only on CI.
