# GitHub Copilot: nested FCO/FCG agent-turn Merkle checkpoints

**Scope and current status:** the repository contains a Copilot instruction profile, custom custody-auditor agent, event schema, local reference construction and test notebook. **Actual Copilot service execution is not yet evidenced** unless a distinct real bot review/execution receipt is recovered. Applies equally to ChatGPT, Claude, LiquidAI/Ollama, on-device ExecuTorch and human reviewers.

## Identity, custody, and causality

A Copilot message, tool invocation or PR review is its own context-specific \`AgentTurnFCO\` event. Versioned instructions, input source/occurrence, prompt, inference output, code edit, test, review and human decision are distinct atoms. Content ID permits deduplication; occurrence ID preserves task/agent/repository/time context. A Git SHA does **not** prove that Copilot executed the code or had a particular model version. Store sanitized/redacted outputs or restrictive access pointers; never put tokens, private lab data or patient data into public evidence.

## Nested levels

| Level | Scope | Verification requirement |
| --- | --- | --- |
| L0 | Raw source/paper/protocol/licensed object and atom/occurrence IDs | exact digest, citation, version, admission rights |
| L1 | One agent turn or short sequence | canonical ordered event leaves, MMR peaks/root, parent-bound predecessor, verifier |
| L2 | A feature task / pull request | ordered **typed L2 leaf events** pointing to separately verified L1 receipts, diffs, notebooks, tests and review judgments |
| L3 | Repo/release and upstream \`gj-labs/xrz\` sync | ordered **typed L3 leaves** with admitted L2 references, rights and human review, independent machine verification |

Higher-level roots are **new commitments to typed envelopes**, not aliases for child roots. FCG relationships like \`DERIVED_FROM\`, \`REVIEWED_BY\`, \`SUPERSEDES\`, \`CONTAINS_RECEIPT\` are declared relationships, not causality.

**Predecessor rule:** the local reference algorithm binds the parent checkpoint digest within the root. That does not itself prove append-only growth; independently retrieve the authenticated predecessor and verify authorized sequencing/append-only consistency before any production admission.

## Local reference digest construction

- Canonicalization \`xrz-json-v1\`: UTF-8, Unicode NFC, lexicographically sorted JSON keys, compact delimiters, no floating point and integers limited to IEEE-754 safe range. This is **not RFC8785/JCS**. Multiple runtimes require independent cross-language test vectors and duplicate-key parsing policy.
- Leaf SHA256(\`XRZ/MMR/v1/leaf\0 || uint64_be(length) || canonical_event_bytes\`).
- Internal node SHA256(\`XRZ/MMR/v1/node\0 || uint16_be(parent_height) || 32-byte-left || 32-byte-right\`).
- Collect binary-carry mountain peaks in left-to-right order.
- Root SHA256(\`XRZ/MMR/v1/root\0 || parent_tagged || uint64_be(leaf_count) || uint16_be(peak_count) || each[uint16_be(height)||32-byte-peak]\`), parent tagged with \`0x00\` for genesis or \`0x01 + 32-byte parent root\`.
- Local dual-path verification uses iterative mountain construction and independent recursive perfect-subtree recomputation **on one host**. This is not a separate device attestation; signing is NOT_SIGNED and project release MMR is NOT_COMMITTED.
- Mutation, reordering, truncation, wrong parent and duplicates must fail validation. Test design is in \`tests/test_agent_turn_mmr.py\`; executable notebook \`notebooks/00_agent_turn_merkle_breakpoints.ipynb\`.

## Copilot workflow

1. Repository-level instructions: \`.github/copilot-instructions.md\` (include per-source citations/licenses, exact notebook/test receipts, STOP on absent SOP authorizations).
2. Optional custodian reviewer agent: \`.github/agents/fco-custody-auditor.agent.md\`. Custom agent activation depends on GitHub Copilot availability/configuration; merely committing an agent file does not mean it ran.
3. GitHub Copilot review may be requested under PR Reviewers → Copilot, or through the reviewer \`copilot-pull-request-reviewer[bot]\` if service enabled. Capture actual reviewer username, comment IDs, PR head commit SHA and review results as distinct L1 occurrence records.
4. Reviewer feedback is advisory. Human owner evaluates and records a decision; test fixes and reruns produce successor checkpoints, not historical rewrites.
5. Copilot coding agent can work from assigned issues if enabled; fork Issues were previously disabled. Never silently push to upstream \`gj-labs/xrz\` or auto-merge unreviewed changes.

**Known gaps:** two-host/cross-language verification, full formal consistency proof, actual Copilot model/tool trace, live Samsung device inference, lab approval and signatures are NOT_TESTED. Publication DOI/preprint completeness remains unresolved.

NEXT_ACTION: obtain a real Copilot review or task receipt, preserve its exact references and independently validate a successor checkpoint; separate human approval still required.
## Review breakpoints and successor contract

GitHub PR #1 now has separate original review occurrences from **Codex** and **GitHub Copilot** on predecessor commit `e20e03f5d4a3044b856757b2acd25a509ad09d6f`. Codex supplied four custody-verifier findings; Copilot supplied four findings including missing typed events and citation/receipt edges. The original comments and v1 contracts are preserved. `contracts/AGENT_TURN.v2.schema.json` requires `event_kind`; `contracts/FCO_FCG_v2.json` extends the canonical declared edge vocabulary; `evidence/synthetic_agent_turn_receipt_v2.json` is a separate synthetic successor root linking the original synthetic v1 root. These are *not* real Copilot task input/output leaves or production attestation. Rerun GitHub CI and record a human decision before admitting changes.
