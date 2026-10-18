# Mandatory scientific citation, licensing and FCO/FCG admission

Every data source, preprint, publication, model weight, prompt, protocol, algorithm, image, recording, figure, equation, table and generated artifact requires an independently addressable **Source** and **Occurrence** when feasible.

### Citation atom
Record source DOI/URL/arXiv/OpenReview/Zenodo ID, exact title, author list, publication/preprint status, date, version, license, page/figure/equation locator, access date, source bytes/hash or verified metadata digest, transformation and rights. Do not invent missing identifiers. Store citation occurrence identity per claim/section/artifact; a reused DOI is one content reference but each citation use has its own occurrence and context.

### FCG graph links
CITES, BUILDS_ON, USES_DATA_FROM, REPRODUCES, COMPARES_WITH, CORRECTS and SUPERSEDES are **declared relationships only**. A citation edge does not establish scientific truth, priority, novelty, authorship or causality. Claims link to exact evidence atoms and independently testable oracles; release artifact links to the cited version and reviewed license.

### Release gate
BLOCK if provenance unknown for redistributable code/data, license incompatible, citation missing, user preprint identity ambiguous, privacy/consent absent, source content altered without successor, scientific result NOT_TESTED, or claimed signature/MMR without verification. Preserve UNKNOWN instead of filling a guessed bibliographic record. Distinguish raw licensed coordinate data from linked journal publication rights and model weight licenses.

### Author preprints
The user's preprint bibliography **has not yet been independently recovered and verified in XRZ**. `governance/PUBLICATION_CITATION_REGISTER.json` includes an explicit unresolved placeholder, not fabricated citations. Before promoting any claim or publishing the stage artifact, recover exact manuscript, DOI/OpenReview/Zenodo/arXiv ID, versions, contributor/author status and license from authoritative sources; create atoms for equations, figures, experiments and reference occurrences. Do not equate acknowledgments, working drafts and published authorship.

### Upstream fork governance
Track `gj-labs/xrz` separately from `biobitworks/xrz`; compare upstream commits, file licenses and breaking schema changes before proposed sync. Never rewrite historical fork source custody.
