#!/usr/bin/env python3
"""Validate local playtest traces and summarize evidence, never certify gameplay.
Usage: python summarize_playtest.py report.jsonl [another.jsonl ...] --output summary.json
No network access; no file is changed unless --output explicitly names a NEW file.
"""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

MAX_BYTES = 4 * 1024 * 1024
MAX_RECORD_BYTES = 16384


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def summarize(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
        raise ValueError("Not a bounded regular report file")
    data = path.read_bytes()
    if len(data) > MAX_BYTES:
        raise ValueError("Report grew past size limit")
    lines = data.splitlines(keepends=True)
    if not lines or len(lines) > 5001:
        raise ValueError("Empty or oversized record count")
    events = []
    elapsed = -1
    for index, line in enumerate(lines, 1):
        if len(line) > MAX_RECORD_BYTES or not line.endswith(b'\n'):
            raise ValueError("Oversized or interrupted record")
        event = json.loads(line, object_pairs_hook=unique_object)
        if not isinstance(event, dict) or set(event) != {'seq', 'elapsedNanos', 'tick', 'kind', 'fields'}:
            raise ValueError("Unexpected event schema")
        if type(event['seq']) is not int or event['seq'] != index:
            raise ValueError("Noncontiguous sequence")
        if type(event['elapsedNanos']) is not int or event['elapsedNanos'] < elapsed:
            raise ValueError("Invalid monotonic time")
        elapsed = event['elapsedNanos']
        if type(event['tick']) is not int or not isinstance(event['kind'], str) or not re.fullmatch(r'[A-Z][A-Z0-9_]{0,47}', event['kind']):
            raise ValueError("Invalid tick/event kind")
        if not isinstance(event['fields'], dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in event['fields'].items()):
            raise ValueError("Invalid event fields")
        events.append(event)
    if events[0]['kind'] != 'SESSION_START' or events[0]['fields'].get('schema') != '1':
        raise ValueError("Missing header or unsupported schema")
    if any(e['kind'] == 'SESSION_START' for e in events[1:]):
        raise ValueError("Multiple sessions in one trace")
    if any(e['kind'] == 'SESSION_END' for e in events[:-1]):
        raise ValueError("Records after terminal footer")
    header = events[0]['fields']
    if header.get('mode') not in ('control', 'seam'):
        raise ValueError("Unknown mode")
    footer = events[-1]['fields'] if events[-1]['kind'] == 'SESSION_END' else None
    counts = Counter(e['kind'] for e in events if e['kind'] != 'SESSION_END')
    issues = []
    if footer is None:
        issues.append('No terminal footer: crash, interruption or still-open capture')
    else:
        if not re.fullmatch(r'[0-9]+', footer.get('dropped', '')) or footer.get('complete') not in ('true', 'false'):
            raise ValueError("Malformed completion footer")
        expected_counts = {"count." + k: str(v) for k, v in counts.items()}
        actual_counts = {k: v for k, v in footer.items() if k.startswith('count.')}
        if expected_counts != actual_counts:
            raise ValueError("Footer counts do not match actual records")
        if int(footer['dropped']) != 0 or footer['complete'] != 'true':
            issues.append('Recorder dropped records: do not use as complete evidence')
    for kind in ('INVENTORY_UNSUPPORTED', 'OBSERVATION_ERROR', 'RESULT_SAMPLE_OMITTED'):
        if counts[kind]:
            issues.append(f'{kind}: {counts[kind]} observation gaps')
    saves = [e['fields'] for e in events if e['kind'] == 'SAVE_CHECK']
    if any(s.get('result') != 'MATCH' for s in saves):
        issues.append('One or more save checks were not MATCH; unsaved changes or unsupported data need investigation')
    game_modes = {e['fields']['gameMode'] for e in events if 'gameMode' in e['fields']}
    if game_modes != {'survival'}:
        issues.append('Not an exclusively Survival-mode capture')
    coverage = {
        'breakEvent': any(e['kind'] == 'BREAK_EVENT' and e['fields'].get('canceledAtObserver') == 'false' for e in events),
        'placeEvent': any(e['kind'] == 'PLACE_EVENT' and e['fields'].get('canceledAtObserver') == 'false' for e in events),
        'simpleUseEvent': bool(counts['USE_BLOCK']),
        'itemTossEvent': bool(counts['ITEM_TOSS']),
        'itemPickupEvent': bool(counts['ITEM_PICKUP']),
        'savedInventoryMatch': any(s.get('result') == 'MATCH' for s in saves),
    }
    canceled = Counter(e['kind'] for e in events if e['fields'].get('canceledAtObserver') == 'true')
    inventory = [e['fields'] for e in events if e['kind'] == 'INVENTORY']
    return {
        'report': path.name, 'label': header.get('label'), 'mode': header.get('mode'),
        'worker': header.get('worker'), 'playerKey': header.get('playerKey'), 'version': header.get('version'),
        'recordingComplete': footer is not None and footer.get('complete') == 'true' and footer.get('dropped') == '0',
        'observationIssues': issues, 'eventCounts': dict(counts), 'canceledAtObserver': dict(canceled),
        'scenarioCoverage': coverage, 'durationSeconds': elapsed / 1_000_000_000,
        'initialInventoryDigest': inventory[0].get('digest') if inventory else None,
        'finalInventoryDigest': inventory[-1].get('digest') if inventory else None,
        'saveChecks': saves,
        'gameplayCertified': False,
        'limitations': [
            'Server event observations are not client visibility or acknowledgement latency.',
            'Coverage does not establish correct loot, effects, claims, atomic recovery or no duplication.',
            'Captures end at logout/backend handoff; start a new capture on the destination worker.',
            'Save MATCH compares ordinary inventory/selection only; it is not a restorable checkpoint.',
            'Different control/seam inventories are not automatically a defect; scripts and initial state must match.',
        ],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports', type=Path, nargs='+')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    output = {'schema': 1, 'gameplayCertified': False, 'reports': [], 'errors': []}
    for path in args.reports:
        try:
            output['reports'].append(summarize(path))
        except (OSError, UnicodeError, ValueError, TypeError, KeyError) as error:
            output['errors'].append({'report': path.name, 'error': str(error)})
    text = json.dumps(output, indent=2, ensure_ascii=True) + '\n'
    if args.output:
        try:
            with args.output.open('x', encoding='utf-8') as stream:
                stream.write(text)
        except OSError as error:
            parser.error(f'Cannot create new output file: {error}')
    else:
        print(text, end='')
    return 1 if output['errors'] or any(not r['recordingComplete'] for r in output['reports']) else 0


if __name__ == '__main__':
    sys.exit(main())
