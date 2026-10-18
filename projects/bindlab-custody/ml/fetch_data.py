"""Build the small bundled datasets in ml/data/ from RCSB PDB.

    python fetch_data.py            # writes data/ppi_pairs.tsv + data/calib.fasta

Positives: two-entity protein heterocomplexes (mostly Protein-Protein Docking
Benchmark 5 entries: enzyme/inhibitor, signalling, a few others). Each entry
gives one (target, binder) pair = its two polymer entities. Negatives are made
later (binding_head.py) by shuffling partners across complexes.

calib.fasta: ~50 distinct chains from the same download, used to calibrate
QNN 16a8w activation ranges. Both outputs are small and are bundled, so this
script only needs to be re-run to regenerate them.
"""

from __future__ import annotations

import argparse
import sys
import time
import urllib.request

from common import DATA, MAX_RESIDUES

# Protein-Protein Docking Benchmark 5 complex IDs (plus a few classics).
# Entries with != 2 protein entities (e.g. Fab + antigen) are skipped.
PDB_IDS = """
1AVX 1AY7 1BVN 1CGI 1CLV 1D6R 1DFJ 1E6E 1EAW 1EWY 1EZU 1F0V 1FLE 1GL1 1GXD
1HIA 1JTG 1MAH 1N8O 1OPH 1OYV 1PPE 1R0R 1TMQ 1UDI 1YVB 2ABZ 2B42 2J0T 2MTA
2O8V 2OUL 2PCC 2SIC 2SNI 2UUY 3SGQ 4CPA 7CEI 1ACB 1BRS 1CHO 1CSE 1EMV 1FSS
1PXV 2PTC 3SGB 1TGS 1BRC 1ATN 1FQ1 1GRN 1H1V 1H9D 1HE1 1HE8 1I2M 1I4D 1IB1
1IBR 1IJK 1J2J 1JIW 1JK9 1JWH 1K5D 1KAC 1KTZ 1KXP 1KXQ 1LFD 1M10 1MQ8 1NW9
1OFU 1PVH 1QA9 1RLB 1RV6 1S1Q 1SBB 1T6B 1US7 1WDW 1WQ1 1XD3 1XQS 1Y64 1Z0K
1Z5Y 1ZHH 1ZHI 1ZM4 2A5T 2A9K 2AJF 2AYO 2B4J 2BTF 2C0L 2CFH 2FD6 2G77 2GAF
2GTP 2H7V 2HLE 2HQS 2HRK 2I25 2I9B 2IDO 2J7P 2NZ8 2OOB 2OOR 2OT3 2OZA 2VDB
2Z0E 3BP8 3CPH 3D5S 3H2V 4FZA 1AK4 1AKJ 1B6C 1BUH 1E96 1EFN 1F51 1FC2 1FCC
1GCQ 1GHQ 1GPW 1HCF 1K74 1ML0 1QFW 1XU1 2AQ3 2HMI 2JEL 2VIS 3EOA 3HMX 1BGX
""".split()

STANDARD = set("ACDEFGHIKLMNPQRSTVWY")


def fetch_entities(pdb_id: str) -> list[tuple[str, str]]:
    url = f"https://www.rcsb.org/fasta/entry/{pdb_id}"
    with urllib.request.urlopen(url, timeout=30) as r:
        text = r.read().decode()
    ents, head, buf = [], None, []
    for line in text.splitlines():
        if line.startswith(">"):
            if head:
                ents.append((head, "".join(buf)))
            head, buf = line[1:], []
        else:
            buf.append(line.strip())
    if head:
        ents.append((head, "".join(buf)))
    return ents


def is_protein(seq: str) -> bool:
    return len(seq) >= 20 and sum(c in STANDARD for c in seq) / len(seq) > 0.9


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n_calib", type=int, default=50)
    a = ap.parse_args()

    DATA.mkdir(parents=True, exist_ok=True)
    pairs, seen = [], set()
    for i, pid in enumerate(dict.fromkeys(PDB_IDS)):
        try:
            ents = [(h, s) for h, s in fetch_entities(pid) if is_protein(s)]
        except Exception as e:  # network hiccup / obsolete entry
            print(f"  {pid}: skip ({e})", file=sys.stderr)
            continue
        if len(ents) != 2 or ents[0][1] == ents[1][1]:
            continue
        (h1, s1), (h2, s2) = sorted(ents, key=lambda e: -len(e[1]))  # target = longer chain
        name = lambda h: h.split("|")[2] if h.count("|") >= 2 else h
        pairs.append((pid, name(h1), s1, name(h2), s2))
        seen.update([s1, s2])
        time.sleep(0.05)
    print(f"kept {len(pairs)} two-entity complexes")

    with open(DATA / "ppi_pairs.tsv", "w") as f:
        f.write("pdb_id\ttarget_name\ttarget_seq\tbinder_name\tbinder_seq\tlabel\n")
        for pid, n1, s1, n2, s2 in pairs:
            f.write(f"{pid}\t{n1}\t{s1}\t{n2}\t{s2}\t1\n")

    # Calibration set: distinct chains that fit the 254-residue window,
    # spread across complexes (both partners), first n_calib.
    calib, used = [], set()
    for pid, n1, s1, n2, s2 in pairs:
        for tag, n, s in (("A", n1, s1), ("B", n2, s2)):
            if s in used or len(s) > MAX_RESIDUES:
                continue
            used.add(s)
            calib.append((f"{pid}_{tag} {n}", s))
    calib = calib[: a.n_calib]
    with open(DATA / "calib.fasta", "w") as f:
        for h, s in calib:
            f.write(f">{h}\n")
            for j in range(0, len(s), 60):
                f.write(s[j:j + 60] + "\n")
    print(f"wrote {DATA / 'ppi_pairs.tsv'} and {DATA / 'calib.fasta'} ({len(calib)} seqs)")


if __name__ == "__main__":
    main()
