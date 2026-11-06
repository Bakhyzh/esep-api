"""Real age/file-system tests; all artifacts and keys are disposable test-owned data."""
import contextlib
from datetime import datetime,timezone,timedelta
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import ops_common as ops

spec=importlib.util.spec_from_file_location('retention',Path(__file__).with_name('backup-retention.py'))
retention=importlib.util.module_from_spec(spec);spec.loader.exec_module(retention)
class ArtifactSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.old=(ops.BACKUPS,ops.KEYS,ops.KEY,retention.BACKUPS)
        ops.BACKUPS=self.root/'backups';ops.KEYS=self.root/'keys';ops.KEY=ops.KEYS/'backup.agekey';retention.BACKUPS=ops.BACKUPS
        self.recipient=ops.keys()
    def tearDown(self):
        ops.BACKUPS,ops.KEYS,ops.KEY,retention.BACKUPS=self.old;self.temp.cleanup()
    def artifact(self,index,days):
        directory=ops.BACKUPS/('esep-backup-fixture-'+str(index));ops.private_dir(directory)
        data={'format':1,'project':'esep-platform-local','createdAt':(datetime.now(timezone.utc)-timedelta(days=days)).isoformat(),'databases':{}}
        for name in ops.DATABASES:
            path=directory/(name+'.dump.age');ops.encrypt_bytes(b'only disposable synthetic artifact bytes',path,self.recipient)
            data['databases'][name]={'ciphertextSha256':ops.sha(path),'facts':{}}
        ops.encrypt_bytes(json.dumps(data).encode(),directory/'manifest.json.age',self.recipient)
        return directory
    def prune(self,*args):
        with patch('sys.argv',['backup-retention.py',*args]),contextlib.redirect_stdout(io.StringIO()):retention.main()
    def test_authenticated_expiration_keeps_recent_minimum_and_incomplete_artifact(self):
        recent=[self.artifact(i,0) for i in range(2)]
        expired=[self.artifact(i+2,20) for i in range(3)]
        incomplete=ops.BACKUPS/'esep-backup-incomplete';ops.private_dir(incomplete);(incomplete/'partial.age').write_bytes(b'incomplete')
        self.prune('--keep','2','--days','7','--apply')
        self.assertTrue(all(p.exists() for p in recent));self.assertTrue(all(not p.exists() for p in expired));self.assertTrue(incomplete.exists())
    def test_modified_ciphertext_is_rejected_before_any_restore(self):
        directory=self.artifact(1,0);path=directory/'ledger_db.dump.age';value=bytearray(path.read_bytes());value[-1]^=1;path.write_bytes(value)
        with self.assertRaisesRegex(RuntimeError,'checksum mismatch'):ops.manifest(directory)
        with self.assertRaisesRegex(RuntimeError,'authentication failed'):ops.authenticate(path)
    def test_missing_restore_identity_is_never_replaced(self):
        ops.KEY=ops.KEYS/'missing.agekey'
        with self.assertRaisesRegex(RuntimeError,'Original backup identity'):ops.keys(create=False)
        self.assertFalse(ops.KEY.exists())
    def test_symlink_refuses_retention_and_preserves_external_files(self):
        outside=self.root/'external';outside.mkdir();sentinel=outside/'preserve';sentinel.write_text('test-owned sentinel')
        (ops.BACKUPS/'esep-backup-symlink').symlink_to(outside,target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError,'Symlink'):self.prune('--apply')
        self.assertTrue(sentinel.exists())
if __name__=='__main__':unittest.main()
