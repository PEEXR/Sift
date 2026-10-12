#!/usr/bin/env python3
"""Unit tests for label_junk_ornith.py.

No model or Ollama server required.
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

TOOLS_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(TOOLS_DIR))

from common import JUNK_TAGS
from label_junk_ornith import PROMPT, SCHEMA, label_one


class TestJunkDefinitions(unittest.TestCase):
    def test_junk_tags_contract(self):
        """Ensure all 4 candidate tags exist with non-empty definitions."""
        expected = {"code", "status", "time_bound", "accidental"}
        self.assertEqual(set(JUNK_TAGS.keys()), expected)
        self.assertIn("one-time password", JUNK_TAGS["code"])
        self.assertIn("transactional status update", JUNK_TAGS["status"])
        self.assertIn("Only useful until a specific date or time", JUNK_TAGS["time_bound"])
        self.assertIn("captured by accident", JUNK_TAGS["accidental"])


class TestPromptAndSchema(unittest.TestCase):
    def test_prompt_content(self):
        """Verify prompt contains required fields and temporal context."""
        for tag in JUNK_TAGS:
            self.assertIn(tag, PROMPT)
        self.assertIn("2026-10-12", PROMPT)
        self.assertIn("YYYY-MM-DD", PROMPT)

    def test_schema_properties(self):
        """Verify schema properties and required fields."""
        self.assertEqual(SCHEMA["type"], "object")
        self.assertEqual(set(SCHEMA["required"]), {"tags", "date", "reason"})
        self.assertEqual(SCHEMA["properties"]["tags"]["items"]["enum"], list(JUNK_TAGS))


class TestLabelOneParsing(unittest.TestCase):
    @patch("urllib.request.urlopen")
    def test_label_one_parsing_valid(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({
            "message": {
                "content": json.dumps({
                    "tags": ["time_bound", "code"],
                    "date": "2026-10-15",
                    "reason": "Shows ticket with expiration date.",
                })
            }
        }).encode()
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        with patch("label_junk_ornith.encode_image", return_value="fakebase64"):
            res = label_one(Path("fake.jpg"))

        self.assertEqual(res["tags"], ["code", "time_bound"])
        self.assertEqual(res["date"], "2026-10-15")
        self.assertEqual(res["reason"], "Shows ticket with expiration date.")

    @patch("urllib.request.urlopen")
    def test_label_one_null_date_and_tag_filter(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({
            "message": {
                "content": json.dumps({
                    "tags": ["code", "unknown_tag", "code"],
                    "date": "null",
                    "reason": "OTP screen.",
                })
            }
        }).encode()
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        with patch("label_junk_ornith.encode_image", return_value="fakebase64"):
            res = label_one(Path("fake.jpg"))

        self.assertEqual(res["tags"], ["code"])
        self.assertIsNone(res["date"])
        self.assertEqual(res["reason"], "OTP screen.")

    @patch("urllib.request.urlopen")
    def test_label_one_iso_date_slice(self, mock_urlopen):
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({
            "message": {
                "content": json.dumps({
                    "tags": ["time_bound"],
                    "date": "2026-11-01T12:00:00Z",
                    "reason": "Flight booking.",
                })
            }
        }).encode()
        mock_response.__enter__.return_value = mock_response
        mock_urlopen.return_value = mock_response

        with patch("label_junk_ornith.encode_image", return_value="fakebase64"):
            res = label_one(Path("fake.jpg"))

        self.assertEqual(res["tags"], ["time_bound"])
        self.assertEqual(res["date"], "2026-11-01")


if __name__ == "__main__":
    unittest.main()
