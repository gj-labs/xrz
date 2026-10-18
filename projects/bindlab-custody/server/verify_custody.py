"""Focused synthetic integrity checks; no hardware or assay claims."""
import base64
import json
import tempfile
from pathlib import Path
import custody as c


def main():
    with tempfile.TemporaryDirectory() as tmp:
        c.DB = Path(tmp)/'journal.sqlite'
        protocol = {'steps': [{'id': 's1', 'required': ['cup'], 'forbidden': ['scissors']}]}
        for _ in range(8):
            r = c.append('r', protocol, 'Observation', {'step_id': 's1', 'detections': c.measurements(
                [{'label': 'cup', 'conf': .9, 'box': [.5,.5,.2,.2]}])})
        assert r['assessment']['status'] == 'OBSERVATION_SUPPORTED'
        for _ in range(3):
            r = c.append('r', protocol, 'Observation', {'step_id': 's1', 'detections': c.measurements(
                [{'label': 'scissors', 'conf': .9, 'box': [.5,.5,.2,.2]}])})
        assert r['assessment']['status'] == 'SUSPECTED_DEVIATION'
        photo = b'\xff\xd8synthetic-marker-fixture\xff\xd9'
        c.append('r', protocol, 'StepReport', {'step_id': 's1'}, photo)
        graph = c.export('r')
        assert graph['signing_state'] == 'NOT_SIGNED'
        assert len(graph['checkpoints'][-1]['ordered_leaves']) == len(graph['checkpoints'])
        db = c.connect()
        rows = c.read(db, 'r')
        try:
            c.verify(list(reversed(rows)), db)
            raise AssertionError('reordering accepted')
        except ValueError: pass
        db.execute('UPDATE photos SET bytes=?', (b'changed',)); db.commit(); db.close()
        try:
            c.export('r'); raise AssertionError('photo mutation accepted')
        except ValueError: pass
        for value in [float('nan'), float('inf')]:
            try:
                c.measurements([{'label':'cup','conf':value,'box':[0,0,1,1]}])
                raise AssertionError('non-finite accepted')
            except ValueError: pass
        # A valid prefix verifies locally: truncation requires a retained external head.
        db = c.connect(); c.verify(rows[:3], db); db.close()
        assert rows[2][1]['root'] != rows[-1][1]['root']
        Path(__file__).with_name('canonical-vectors.json').write_text(json.dumps({
            'canonicalization': 'xrz-json-v1', 'event': {'sequence': 0, 'label': 'cup'},
            'canonical_utf8_hex': c.canonical({'sequence':0,'label':'cup'}).hex(),
            'leaf': c.leaf({'sequence':0,'label':'cup'}),
            'checkpoint': c.checkpoint([{'sequence':0,'label':'cup'}], None)
        }, indent=2)+'\n')
    print('PASS: temporal support/deviation, replay, order rejection, photo mutation, non-finite rejection, truncation limitation')


if __name__ == '__main__': main()
