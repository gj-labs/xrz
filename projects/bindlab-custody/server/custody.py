"""Local, unsigned FCG evidence journal. No device or scientific attestation."""
import base64
import hashlib
import json
import math
import os
import sqlite3
import time
import unicodedata
import uuid
from pathlib import Path

DB = Path(os.environ.get('BINDLAB_CUSTODY_DB') or Path(__file__).with_name('custody.sqlite'))
POLICY = {'id': 'bindlab-temporal-v1', 'window': 8, 'confidence': '0.5',
          'max_gap_ms': 1500, 'max_age_ms': 3000, 'forbidden_frames': 3,
          'max_center_variance': '0.01', 'status': 'PROVISIONAL_ENGINEERING_ONLY'}


def canonical(value):
    def check(v):
        if v is None or type(v) is bool:
            return
        if type(v) is int and abs(v) <= 2**53 - 1:
            return
        if type(v) is str and unicodedata.normalize('NFC', v) == v:
            return
        if type(v) is list:
            for x in v: check(x)
            return
        if type(v) is dict and all(type(k) is str for k in v):
            for k, x in v.items(): check(k); check(x)
            return
        raise ValueError('xrz-json-v1 requires NFC strings, safe integers, no floats')
    check(value)
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def digest(value):
    return sha(canonical(value))


def leaf(event):
    b = canonical(event)
    return sha(b'XRZ/MMR/v1/leaf\0' + len(b).to_bytes(8, 'big') + b)


def node(height, left, right):
    return sha(b'XRZ/MMR/v1/node\0' + height.to_bytes(2, 'big') + bytes.fromhex(left + right))


def root(leaves, peaks, parent):
    packed = b'\0' if parent is None else b'\1' + bytes.fromhex(parent)
    packed += len(leaves).to_bytes(8, 'big') + len(peaks).to_bytes(2, 'big')
    packed += b''.join(h.to_bytes(2, 'big') + bytes.fromhex(x) for h, x in peaks)
    return sha(b'XRZ/MMR/v1/root\0' + packed)


def checkpoint(events, parent):
    leaves, peaks = [leaf(e) for e in events], []
    for x in leaves:
        h = 0
        while peaks and peaks[-1][0] == h:
            x = node(h + 1, peaks.pop()[1], x)
            h += 1
        peaks.append([h, x])
    # Separate recursive mountain construction checks the iterative accumulator.
    def tree(xs):
        if len(xs) == 1: return [0, xs[0]]
        a, b = tree(xs[:len(xs)//2]), tree(xs[len(xs)//2:])
        return [a[0]+1, node(a[0]+1, a[1], b[1])]
    reference, offset = [], 0
    while offset < len(leaves):
        n = 1 << (len(leaves)-offset).bit_length()-1
        reference.append(tree(leaves[offset:offset+n])); offset += n
    if peaks != reference: raise ValueError('MMR verification failed')
    return {'algorithm_version': 'xrz-domain-separated-mmr-v1', 'canonicalization': 'xrz-json-v1',
            'ordered_leaves': leaves, 'leaf_count': len(leaves), 'peaks': peaks,
            'root': root(leaves, peaks, parent), 'parent_checkpoint': parent,
            'signing_state': 'NOT_SIGNED', 'state': 'MMR_COMMITTED',
            'verification_receipt': 'LOCAL_DUAL_PATH_ONLY'}


def connect():
    c = sqlite3.connect(DB, timeout=15)
    c.execute('PRAGMA journal_mode=WAL')
    c.executescript('''
      CREATE TABLE IF NOT EXISTS journal(run TEXT, seq INTEGER, event TEXT, receipt TEXT,
        PRIMARY KEY(run,seq));
      CREATE TABLE IF NOT EXISTS photos(hash TEXT PRIMARY KEY, bytes BLOB);
    ''')
    return c


def read(c, run):
    return [(json.loads(a), json.loads(b)) for a, b in c.execute(
        'SELECT event,receipt FROM journal WHERE run=? ORDER BY seq', (run,))]


def verify(rows, c):
    events, parent = [], None
    for e, receipt in rows:
        if e['sequence'] != len(events) or e['parent_checkpoint'] != parent:
            raise ValueError('journal ordering/parent corruption')
        if digest(e['payload']) != e['content_id']: raise ValueError('content corruption')
        photo = e['payload'].get('photo')
        if photo:
            row = c.execute('SELECT bytes FROM photos WHERE hash=?', (photo['raw_bytes_sha256'],)).fetchone()
            if not row or sha(row[0]) != photo['raw_bytes_sha256'] or len(row[0]) != photo['content_length']:
                raise ValueError('photo integrity failure')
        events.append(e)
        if checkpoint(events, parent) != receipt: raise ValueError('checkpoint corruption')
        parent = receipt['root']
    return events


def measurements(items):
    if not isinstance(items, list) or len(items) > 100: raise ValueError('detection limit')
    result = []
    for d in items:
        label, conf, box = d['label'], d['conf'], d['box']
        if not isinstance(label, str) or not 0 < len(label) <= 80: raise ValueError('label')
        if len(box) != 4: raise ValueError('box requires normalized cx,cy,w,h')
        values = [conf, *box]
        if any(type(x) not in (int, float) or not math.isfinite(x) or not 0 <= x <= 1 for x in values):
            raise ValueError('non-finite or out-of-range detection')
        result.append({'label': label, 'conf': str(conf), 'box': [str(x) for x in box]})
    canonical(result)
    return result


def evaluate(events, step, now):
    frames = [e for e in events if e['event_type'] == 'Observation' and e['payload']['step_id'] == step['id']][-8:]
    reasons, stats = [], {}
    if len(frames) < 8: reasons.append('INSUFFICIENT_OBSERVATIONS')
    if frames and (now-frames[-1]['wall_time'] > POLICY['max_age_ms'] or any(
            b['wall_time']-a['wall_time'] > POLICY['max_gap_ms'] for a, b in zip(frames, frames[1:]))):
        reasons.append('STALE_OR_GAPPED_STREAM')
    for label in sorted(set(step['required'] + step['forbidden'])):
        selected = []
        for e in frames:
            ds = [d for d in e['payload']['detections'] if d['label'] == label and float(d['conf']) > .5]
            selected.append(max(ds, key=lambda d: float(d['conf'])) if ds else None)
        ds = [d for d in selected if d]
        variance = []
        for axis in range(4):
            xs = [float(d['box'][axis]) for d in ds]
            mean = sum(xs)/len(xs) if xs else 0
            variance.append(sum((x-mean)**2 for x in xs)/len(xs) if xs else 0)
        confidences = [float(d['conf']) for d in ds]
        mean_conf = sum(confidences)/len(confidences) if confidences else 0
        stats[label] = {'present': len(ds), 'box_population_variance': [str(x) for x in variance],
                        'confidence_mean': str(mean_conf), 'confidence_population_variance': str(
                            sum((x-mean_conf)**2 for x in confidences)/len(confidences) if confidences else 0)}
        if label in step['required'] and (len(ds) != 8 or any(x > .01 for x in variance[:2])):
            reasons.append('MISSING_OR_UNSTABLE:' + label)
        if label in step['forbidden'] and any(all(selected[i:i+3]) for i in range(max(0, len(selected)-2))):
            reasons.append('SUSPECTED_FORBIDDEN_OBJECT:' + label)
    return {'status': 'SUSPECTED_DEVIATION' if any(r.startswith('SUSPECTED') for r in reasons)
            else 'HOLD' if reasons else 'OBSERVATION_SUPPORTED', 'reasons': reasons,
            'variance': stats, 'observation_ids': [e['occurrence_id'] for e in frames],
            'policy': POLICY, 'policy_hash': digest(POLICY), 'approval': 'HUMAN_REVIEW_REQUIRED',
            'limitations': 'Class-level proxy; no object tracking, action recognition, or proof of protocol error.'}


def append(run, protocol, kind, payload, photo_bytes=None):
    c = connect()
    try:
        c.execute('BEGIN IMMEDIATE')
        rows = read(c, run)
        events = verify(rows, c)
        frozen = events[0]['payload']['protocol'] if events else protocol
        if digest(frozen) != digest(protocol): raise ValueError('protocol changed; create a new run')
        now = time.time_ns()//1_000_000
        if kind == 'Review':
            accepted = [e for e in events if e['event_type'] == 'Review' and e['payload']['accepted']]
            index = len(accepted)
            if index >= len(frozen['steps']) or frozen['steps'][index]['id'] != payload['step_id']:
                raise ValueError('duplicate or out-of-order review')
            reports = [e for e in events if e['event_type'] == 'StepReport' and e['payload']['step_id'] == payload['step_id']]
            if not reports: raise ValueError('photo-backed step report required')
            payload['evidence_ids'] = [reports[-1]['occurrence_id']]
            payload['identity_attestation'] = 'SELF_ASSERTED_LOCAL_REVIEWER'
        parent = rows[-1][1]['root'] if rows else None
        def add(event_kind, body):
            nonlocal parent
            e = {'schema': 'bindlab.fco.v1', 'event_type': event_kind, 'event_id': uuid.uuid4().hex,
                 'occurrence_id': uuid.uuid4().hex, 'content_id': digest(body), 'payload': body,
                 'sequence': len(events), 'wall_time': now, 'monotonic_time': time.monotonic_ns()//1_000_000,
                 'source_device_pseudonym': 'local-receiver', 'context_id': run,
                 'parent_checkpoint': parent}
            events.append(e)
            cp = checkpoint(events, parent)
            c.execute('INSERT INTO journal VALUES(?,?,?,?)', (run,e['sequence'],canonical(e).decode(),canonical(cp).decode()))
            parent = cp['root']
            return e, cp
        if not events:
            add('ExperimentRun', {'experiment_id': 'experiment:'+run, 'run_id': run,
                'protocol': protocol, 'protocol_version': digest(protocol), 'source_provenance': 'UNKNOWN',
                'model_provenance': 'UNKNOWN', 'stream_semantics': 'SERVER_RECEIPT_ORDER_NOT_DEVICE_CAPTURE_ORDER'})
        if photo_bytes is not None:
            h = sha(photo_bytes)
            c.execute('INSERT OR IGNORE INTO photos VALUES(?,?)', (h,photo_bytes))
            payload['photo'] = {'raw_bytes_sha256': h, 'content_length': len(photo_bytes),
                                'source_id': 'sha256:'+h, 'provenance': 'UNKNOWN', 'media_type': 'image/jpeg'}
        e, cp = add(kind, payload)
        step = next((s for s in frozen['steps'] if s['id'] == payload.get('step_id')), None)
        assessment = evaluate(events, step, now) if step else None
        if assessment:
            _, cp = add('Claim', {'step_id': step['id'], 'evidence_ids': [e['occurrence_id']], **assessment})
        c.commit()
        return {'occurrence_id': e['occurrence_id'], 'checkpoint': cp, 'assessment': assessment}
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


def decode_photo(value):
    if not value or len(value) > 8_000_000: raise ValueError('JPEG required, maximum 6MB')
    data = base64.b64decode(value, validate=True)
    if not data.startswith(b'\xff\xd8') or not data.endswith(b'\xff\xd9'): raise ValueError('JPEG markers required')
    return data


def export(run):
    c = connect()
    try:
        rows = read(c, run)
        events = verify(rows, c)
        nodes = [{'id': 'experiment:'+run, 'type': 'Experiment'}, {'id': run, 'type': 'Run'}]
        edges = [{'from': run, 'to': 'experiment:'+run, 'type': 'OCCURRED_ON'}]
        steps, sources = set(), set()
        for e in events:
            nodes.append({'id': e['occurrence_id'], 'type': e['event_type'], **e})
            sid = e['payload'].get('step_id')
            target = run + ':' + sid if sid else run
            if sid and sid not in steps:
                nodes.append({'id': target, 'type': 'ProtocolStep', 'step_id': sid,
                              'protocol_version': events[0]['payload']['protocol_version']})
                edges.append({'from': target, 'to': run, 'type': 'OCCURRED_ON'}); steps.add(sid)
            edges.append({'from': e['occurrence_id'], 'to': target, 'type': 'OCCURRED_ON'})
            for obs in set(e['payload'].get('observation_ids', []) + e['payload'].get('evidence_ids', [])):
                edges.append({'from': e['occurrence_id'], 'to': obs, 'type': 'DERIVED_FROM'})
            photo = e['payload'].get('photo')
            if photo:
                if photo['source_id'] not in sources:
                    nodes.append({'id': photo['source_id'], 'type': 'Source', **photo})
                    sources.add(photo['source_id'])
                edges.append({'from': e['occurrence_id'], 'to': photo['source_id'], 'type': 'DERIVED_FROM'})
        return {'schema': 'bindlab.fcg.v1', 'nodes': nodes, 'edges': edges,
                'checkpoints': [r for _, r in rows], 'integrity': 'LOCALLY_VERIFIED', 'signing_state': 'NOT_SIGNED'}
    finally:
        c.close()
