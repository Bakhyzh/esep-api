import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import ops_common as ops
class ProviderSelectionTest(unittest.TestCase):
    def test_default_and_verified_clone_selection(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(ops,'ROOT',Path(directory)):
            self.assertEqual(ops.provider_database(),'keycloak_db')
            p=Path(directory)/'.env.identity-secure';p.write_text('KEYCLOAK_SECURE_DB_NAME=keycloak_secure_20261107000000_abcdef\n');p.chmod(0o600)
            self.assertEqual(ops.provider_database(),'keycloak_secure_20261107000000_abcdef')
    def test_unsafe_name_or_permissions_rejected(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(ops,'ROOT',Path(directory)):
            p=Path(directory)/'.env.identity-secure';p.write_text('KEYCLOAK_SECURE_DB_NAME=user_db\n');p.chmod(0o600)
            with self.assertRaises(RuntimeError):ops.provider_database()
            p.write_text('KEYCLOAK_SECURE_DB_NAME=keycloak_secure_20261107000000_abcdef\n');p.chmod(0o644)
            with self.assertRaises(RuntimeError):ops.provider_database()
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(ops,'ROOT',Path(directory)):
            target=Path(directory)/'private';target.write_text('KEYCLOAK_SECURE_DB_NAME=keycloak_secure_20261107000000_abcdef\n');target.chmod(0o600)
            (Path(directory)/'.env.identity-secure').symlink_to(target)
            with self.assertRaises(RuntimeError):ops.provider_database()
