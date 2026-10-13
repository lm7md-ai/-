import unittest
from verify_release import RELEASE_CERTIFICATE, verify_identity

class ReleaseIdentityTest(unittest.TestCase):
    def test_classic_update_requires_compatible_verified_baseline(self):
        candidate = ("sa.rihla", 20, {RELEASE_CERTIFICATE})
        verify_identity(candidate, "classic", ("sa.rihla", 4, {RELEASE_CERTIFICATE}))
        for previous in (None, ("sa.rihla.next", 4, {RELEASE_CERTIFICATE}),
                         ("sa.rihla", 20, {RELEASE_CERTIFICATE}), ("sa.rihla", 4, {"different"})):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                verify_identity(candidate, "classic", previous)

    def test_new_edition_is_accepted_and_conflicting_identity_or_key_is_refused(self):
        verify_identity(("sa.rihla.next", 20, {RELEASE_CERTIFICATE}))
        for identity in (("sa.rihla", 20, {RELEASE_CERTIFICATE}), ("sa.rihla.next", 19, {RELEASE_CERTIFICATE}),
                         ("sa.rihla.next", 21, {"different"})):
            with self.assertRaises(ValueError):
                verify_identity(identity)

if __name__ == "__main__":
    unittest.main()
