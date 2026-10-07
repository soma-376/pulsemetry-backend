#!/usr/bin/env python3
"""Unix 설치 스크립트의 순서·실패 중단을 가짜 다운로드/앱으로 검증한다."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "apps/enrollment-api/src/main/resources/bootstrap/install.sh"


class BootstrapTest(unittest.TestCase):
    def run_install(self, os_name, failure=""):
        temp = tempfile.TemporaryDirectory(prefix="pulsemetry-bootstrap-test-")
        self.addCleanup(temp.cleanup)
        home = Path(temp.name)
        stubs = home / "stubs"
        stubs.mkdir()
        env = dict(os.environ, HOME=str(home), PATH=f"{stubs}:{os.environ['PATH']}", TEST_OS=os_name, TEST_FAILURE=failure, TEST_LOG=str(home / "calls"))

        def stub(name, source):
            file = stubs / name
            file.write_text("#!/bin/sh\nset -eu\n" + source)
            file.chmod(0o700)

        stub("uname", 'if [ "$1" = -s ]; then echo "$TEST_OS"; else echo arm64; fi\n')
        stub("curl", '''case "$2" in
  *pulsemetry_gui_*)
    [ "$TEST_FAILURE" != download ] || exit 22
    cp "$HOME/gui-source" "$4" ;;
  *) cp "$HOME/cli-source" "$4" ;;
esac
''')
        stub("hdiutil", '''if [ "$1" = attach ]; then
  mkdir -p "$5/Pulsemetry.app/Contents/MacOS"
  cp "$HOME/gui-source" "$5/Pulsemetry.app/Contents/MacOS/Pulsemetry"
fi
''')
        stub("ditto", 'mkdir -p "$2"\ncp -R "$1/" "$2/"\n')
        # 백그라운드 GUI가 테스트 종료 이후 로그를 쓰지 않도록 시작 인수만 기록한다.
        stub("nohup", 'printf "start-gui\\n" >> "$TEST_LOG"\n')
        for name in ["cli", "gui"]:
            file = home / f"{name}-source"
            file.write_text(f'#!/bin/sh\nprintf "{name}:%s\\n" "$1" >> "$TEST_LOG"\n' + ('[ "$TEST_FAILURE" != register ]\n' if name == "gui" else 'exit 0\n'))
            file.chmod(0o700)
        run = subprocess.run(["sh", str(SCRIPT)], env=env, text=True, capture_output=True)
        calls = (home / "calls").read_text().splitlines() if (home / "calls").exists() else []
        return home, run, calls

    def test_both_platforms_register_before_enrollment(self):
        for os_name in ["Linux", "Darwin"]:
            with self.subTest(os=os_name):
                home, run, calls = self.run_install(os_name)
                self.assertEqual(run.returncode, 0, run.stderr)
                self.assertEqual(calls[:3], ["cli:register-product", "gui:--register-product", "cli:enroll"])
                self.assertTrue((home / ".pulsemetry/bin/pulsemetry").exists())

    def test_missing_gui_does_not_install_cli(self):
        home, run, calls = self.run_install("Linux", "download")
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse((home / ".pulsemetry").exists())
        self.assertEqual(calls, [])

    def test_failed_registration_does_not_consume_invitation(self):
        _, run, calls = self.run_install("Linux", "register")
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(calls, ["cli:register-product", "gui:--register-product"])


if __name__ == "__main__":
    unittest.main()
