"""Strict playtest report parser tests; fixtures are not gameplay evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('summarize_playtest', Path(__file__).parents[1] / 'tools/summarize_playtest.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class ReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'playtest-fixture.jsonl'
        self.rows = [
            dict(seq=1, elapsedNanos=0, tick=-1, kind='SESSION_START', fields=dict(schema='1', mode='control', label='fixture', gameMode='survival')),
            dict(seq=2, elapsedNanos=1, tick=1, kind='MARK', fields=dict(label='begin')),
            dict(seq=3, elapsedNanos=2, tick=-1, kind='SESSION_END', fields={'complete':'true', 'dropped':'0', 'count.SESSION_START':'1', 'count.MARK':'1'}),
        ]
    def write(self):
        self.file.write_text(''.join(json.dumps(r)+'\n' for r in self.rows), encoding='utf-8')
    def test_complete_does_not_certify_gameplay(self):
        self.write(); result=mod.summarize(self.file)
        self.assertTrue(result['recordingComplete']); self.assertFalse(result['gameplayCertified'])
        self.assertFalse(any(result['scenarioCoverage'].values()))
    def test_missing_footer_is_incomplete(self):
        self.rows.pop(); self.write(); self.assertFalse(mod.summarize(self.file)['recordingComplete'])
    def test_drops_are_incomplete(self):
        self.rows[-1]['fields'].update(complete='false', dropped='1'); self.write()
        self.assertFalse(mod.summarize(self.file)['recordingComplete'])
    def test_sequence_gap_rejected(self):
        self.rows[1]['seq']=4; self.write()
        with self.assertRaises(ValueError): mod.summarize(self.file)
    def test_duplicate_keys_rejected(self):
        self.write(); self.file.write_text(self.file.read_text().replace('"seq": 1','"seq": 1, "seq": 1'))
        with self.assertRaises(ValueError): mod.summarize(self.file)
    def test_wrong_footer_counts_rejected(self):
        self.rows[-1]['fields']['count.MARK']='7'; self.write()
        with self.assertRaises(ValueError): mod.summarize(self.file)
    def test_partial_tail_rejected(self):
        self.write(); self.file.write_bytes(self.file.read_bytes()[:-1])
        with self.assertRaises(ValueError): mod.summarize(self.file)
    def test_unknown_schema_rejected(self):
        self.rows[0]['fields']['schema']='2'; self.write()
        with self.assertRaises(ValueError): mod.summarize(self.file)
    def test_post_footer_record_rejected(self):
        self.rows.append(dict(seq=4, elapsedNanos=3,tick=2,kind='MARK',fields={}));self.write()
        with self.assertRaises(ValueError):mod.summarize(self.file)
    def test_nonmonotonic_timestamp_rejected(self):
        self.rows[-1]['elapsedNanos']=0;self.write()
        with self.assertRaises(ValueError):mod.summarize(self.file)
    def test_cli_never_overwrites(self):
        self.write();out=Path(self.temp.name)/'summary.json'
        self.assertEqual(mod.main([str(self.file),'--output',str(out)]),0)
        before=out.read_bytes()
        with self.assertRaises(SystemExit):mod.main([str(self.file),'--output',str(out)])
        self.assertEqual(before,out.read_bytes())
    def test_wrong_fields_types_rejected(self):
        self.rows[1]['fields']['label']=15;self.write()
        with self.assertRaises(ValueError):mod.summarize(self.file)

if __name__=='__main__':unittest.main()
