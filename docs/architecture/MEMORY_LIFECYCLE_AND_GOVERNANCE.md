# PersonalAI Memory Lifecycle and Governance Specification

## 1. Overview and Core Principles

PersonalAI establishes a local-first, privacy-respecting memory engine that captures, organizes, and utilizes user context. Memory governance ensures that:
1. **User Control is Absolute**: No AI inference becomes a permanent authoritative fact without explicit user review and confirmation.
2. **Local-First Processing**: Extraction, storage, search, and retrieval operate on-device by default.
3. **Data Provenance**: Every memory item retains verifiable lineage, including source message IDs, timestamps, extraction types, and confirmation history.
4. **Resilient Mutation Propagation**: Corrections, invalidations, and deletions deterministically propagate across database tables, prompt assemblies, caches, and future embedding indexes.

---

## 2. Memory Taxonomy and Classifications

To avoid conflating transient dialogue with long-term personal truths, PersonalAI classifies memory items into distinct lifecycle tiers:

| Tier / Classification | Semantic Meaning | Mutability & Persistence | Authority Level |
| :--- | :--- | :--- | :--- |
| **Explicit User Facts** | Statements explicitly asserted by the user (e.g., *"I have a peanut allergy"*). | Persistent across sessions; mutable by user edit/deletion. | High / Authoritative |
| **AI-Inferred Preferences**| Nuances deduced from behavior or tone (e.g., *"Prefers concise code snippets"*). | Candidate until confirmed; subject to decay/re-evaluation. | Medium / Tentative |
| **Temporary Context** | Operational details valid only for the immediate session or task (e.g., current location, draft document). | Session-scoped; automatically pruned after task completion or inactivity. | Transient |
| **Short-Term Plans** | Action items with explicit deadlines or near-term relevance (e.g., *"Pick up groceries today"*). | Valid until due timestamp or completion; archives to completed tasks. | Time-bound |
| **Long-Term Goals** | Strategic objectives spanning weeks or months (e.g., *"Train for a marathon"*). | Persistent; periodically reviewed for progress during Daily Review. | High / Authoritative |
| **Confirmed Memories** | Items reviewed and approved by the user via Daily Memory Review. | Stored in official `memories` table; injected into prompt context. | Authoritative |
| **Legacy Unverified Memories** | Historical memories recorded prior to schema v8 where provenance or explicit user approval cannot be proven. Classified as `LEGACY_UNVERIFIED`. | Preserved with original ID and content; treated as non-authoritative. | Low / Non-Authoritative |
| **Corrected / Invalidated Memories** | Prior memories superseded by newer facts or explicitly marked invalid by the user. | Soft-deleted or tombstoned in database; completely excluded from AI context. | Inactive / Deprecated |
| **Deleted Memories** | Purged items where content is permanently removed. Minimal non-content tombstone metadata may remain to prevent resurrection. | Permanently removed from active storage and search indexes. | Void |

### Handling Legacy Data (`LEGACY_UNVERIFIED`)
Legacy records generated before the Daily Memory Review migration lack strict extraction metadata and user confirmations. Under this governance framework:
- Existing IDs and content are preserved intact without data loss.
- Legacy records are **never automatically converted** into confirmed authoritative facts.
- Legacy records are flagged as `LEGACY_UNVERIFIED`.
- When presented in UI or used in prompts, legacy memories are explicitly annotated as unverified context rather than authoritative personal truths.

---

## 3. Daily Memory Review Lifecycle

Candidate memories extracted by `ExtractCandidateMemoriesUseCase` enter the system in a staged isolation layer (`candidate_memories` table) and follow this deterministic lifecycle:

```
                  ┌──────────────────────┐
                  │ Background Extraction│
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │   Candidate Memory   │  (Status: PENDING)
                  │ (candidate_memories) │
                  └──────────┬───────────┘
                             │
           ┌─────────────────┼─────────────────┐
           │                 │                 │
           ▼                 ▼                 ▼
     [ User Edit ]     [ User Approve ]  [ User Discard ]
           │                 │                 │
           └────────┬────────┘                 ▼
                    │                   Status = DISCARDED
                    ▼
          Room Transaction:
          1. Verify candidate is PENDING
          2. Insert/Update into `memories` table
          3. Set candidate status = APPROVED
```

### Review Operations Defined
1. **Confirm / Approve**: Promotes the candidate memory into the authoritative `memories` table. The candidate record status transitions to `APPROVED`.
2. **Correct / Edit**: Allows the user to refine the content, topic, or category prior to approval. The modified text is stored in `memories`, maintaining the audit trail link to the original dialogue snippet.
3. **Reclassify**: Changes the semantic category (e.g., from `USER_PREFERENCE` to `LONG_TERM_GOAL`) to ensure accurate prompt indexing.
4. **Discard**: Marks candidate status as `DISCARDED`. The candidate is removed from pending UI lists and will not be promoted to long-term memory.
5. **Delete**: Completely purges the candidate or memory record from disk.

---

## 4. Propagation of Corrections and Deletions

To prevent "zombie memories" (where deleted or corrected facts reappear due to stale caches, background tasks, or search indices), changes must propagate atomically:

### 1. Database Tier (Room / SQLite)
- All memory promotions and updates execute within a database transaction (`database.withTransaction`).
- Deletions issue an immediate `DELETE FROM memories WHERE id = :id`.
- Deletion tombstone metadata (hash of deleted snippet or message ID) is optionally recorded to ensure background extraction loops do not re-extract the identical fact from historic messages.

### 2. Retrieval & Context Assembly Tier
- Prompts retrieve only memories satisfying `status = 'CONFIRMED'` or `category != 'DISCARDED'`.
- Any memory with an active invalidation tombstone is stripped during retrieval ranking.

### 3. Prompt Cache and LLM Context Window
- Chained conversation IDs (such as OpenAI Responses `previous_response_id`) are invalidated and reset upon memory deletion or major fact correction to avoid model context retention.

### 4. Background Extraction Tier
- The extraction cursor (`LAST_REVIEWED_MESSAGE_ID`) advances strictly upon successful processing. Once a message range has been scanned, background loops never rescan past ranges unless explicitly commanded by a user re-index request.

### 5. Vector Search and Embeddings (Future Integration)
- Any vector index or embedding cache must subscribe to Room database change flows via invalidation trackers.
- Deleted memory IDs trigger immediate removal from vector indices before returning search results to the AI router.

---

## 5. Distinction Between Memory Mutation Operations

To ensure clarity in application logic:
- **Correction**: Replaces erroneous text with updated user-provided facts while maintaining the original memory ID.
- **Retraction**: User states an earlier fact is no longer true (e.g., *"I don't play guitar anymore"*). The memory is marked expired or archived.
- **Supersession**: A new fact automatically takes precedence over an older conflicting fact (e.g., new city address replaces old city address).
- **Reclassification**: Changes the organizational category without altering factual assertions.
- **Deletion**: Complete physical removal of the content. Non-content metadata (timestamp, ID hash) may remain in a lightweight tombstone table purely to prevent re-extraction.
