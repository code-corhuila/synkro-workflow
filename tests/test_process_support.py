"""Owner-independent fallback cleanup registration safety."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from process_acceptance import execution_id

class ProcessSupportTest(unittest.TestCase):
    def test_records_only_a_marked_local_verifier_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent=Path(temporary)/('run-'+'a'*32);parent.mkdir()
            (parent/'.local-owner').write_text(parent.name)
            file=parent/'.execution-ids'
            with patch.dict(os.environ,{'SYNKRO_LOCAL_OWNER_FILE':str(file)}):identifier=execution_id()
            self.assertRegex(identifier,r'^[0-9a-f]{32}$')
            self.assertEqual(file.read_text(),identifier+'\n')

    def test_rejects_unrelated_file_without_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            file=Path(temporary)/'user-file';file.write_text('preserve')
            with patch.dict(os.environ,{'SYNKRO_LOCAL_OWNER_FILE':str(file)}):
                with self.assertRaises(RuntimeError):execution_id()
            self.assertEqual(file.read_text(),'preserve')

if __name__=='__main__':unittest.main()
