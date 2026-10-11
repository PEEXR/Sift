#!/usr/bin/env python3
"""Unit tests for pure parsing functions in label_gemma4.py.

No model or hardware required. Tests:
- Keys-only output parsing
- Batch line parsing (keys-only)
- Batch JSON parsing
- Whole-word fallback parser
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

# Add tools/phase0 to sys.path so imports work regardless of cwd
TOOLS_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(TOOLS_DIR))

from label_gemma4 import (
    parse_batch_json,
    parse_batch_lines,
    parse_keys_only,
    parse_output,
)


class TestKeysOnlyParsing(unittest.TestCase):
    def test_comma_separated(self):
        """Standard comma-separated list of category keys."""
        self.assertEqual(parse_keys_only("tweets, memes"), ["memes", "tweets"])

    def test_none_variants(self):
        """'none', 'None', and 'none.' must return an empty list."""
        self.assertEqual(parse_keys_only("None"), [])
        self.assertEqual(parse_keys_only("none"), [])
        self.assertEqual(parse_keys_only("none."), [])

    def test_trailing_punctuation(self):
        """Trailing dots and punctuation must be stripped before matching keys."""
        self.assertEqual(parse_keys_only("tweets."), ["tweets"])
        self.assertEqual(parse_keys_only("  tweets,  "), ["tweets"])
        self.assertEqual(parse_keys_only("tweets, memes."), ["memes", "tweets"])

    def test_garbage(self):
        """Garbage text without valid category keys returns []."""
        self.assertEqual(parse_keys_only("garbage"), [])
        self.assertEqual(parse_keys_only("hello world foo bar"), [])
        self.assertEqual(parse_keys_only(""), [])

    def test_whitespace_and_mixed(self):
        """Whitespace-separated or mixed commas/whitespace."""
        self.assertEqual(parse_keys_only("tweets memes"), ["memes", "tweets"])
        self.assertEqual(parse_keys_only("chats,  study,   notes"), ["chats", "notes", "study"])


class TestBatchLineParsing(unittest.TestCase):
    def test_standard_lines(self):
        """Standard batch keys-only lines."""
        raw = "1: tweets, memes\n2: none"
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["memes", "tweets"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": False})

    def test_spacing_and_prefix_variants(self):
        """Variations like '2:none', '1. tweets', or 'Image 1: tweets'."""
        raw = "1: tweets\n2:none"
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["tweets"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": False})

        raw_prefixed = "Image 1: tweets\nImage 2: none"
        parsed_prefixed = parse_batch_lines(raw_prefixed, batch_size=2)
        self.assertEqual(parsed_prefixed[1], {"labels": ["tweets"], "is_missing": False})
        self.assertEqual(parsed_prefixed[2], {"labels": [], "is_missing": False})

    def test_missing_line(self):
        """If a line for an index is omitted, record missing."""
        raw = "1: tweets"
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["tweets"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": True})

    def test_extra_lines(self):
        """Conversational text or comments surrounding lines must be ignored."""
        raw = (
            "Here are the classifications:\n"
            "1: tweets\n"
            "Image 2 was difficult to identify.\n"
            "2: none\n"
            "Hope this helps!\n"
        )
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["tweets"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": False})

    def test_out_of_range_index(self):
        """Indices outside 1..batch_size must not overwrite valid indices."""
        raw = "0: memes\n1: chats\n5: tweets"
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["chats"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": True})

    def test_unparsable_line(self):
        """Lines that contain garbage (neither 'none' nor valid keys) are treated as missing."""
        raw = "1: tweets\n2: random garbage text"
        parsed = parse_batch_lines(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["tweets"], "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "is_missing": True})


class TestBatchJsonParsing(unittest.TestCase):
    def test_standard_json_array(self):
        """Standard JSON array with indices, labels, and sources."""
        raw = '[{"i": 1, "labels": ["tweets"], "source": "x"}, {"i": 2, "labels": [], "source": "unknown"}]'
        parsed = parse_batch_json(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["tweets"], "source": "x", "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "source": "unknown", "is_missing": False})

    def test_missing_and_extra_indices(self):
        """Missing indices in JSON array are marked missing; out of range ignored."""
        raw = '[{"i": 1, "labels": ["chats"]}, {"i": 4, "labels": ["memes"]}]'
        parsed = parse_batch_json(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["chats"], "source": "unknown", "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "source": "unknown", "is_missing": True})

    def test_malformed_json_fallback(self):
        """Malformed outer JSON recovers individual objects or marks missing."""
        raw = 'Random text {"i": 1, "labels": ["study"]} trailing text'
        parsed = parse_batch_json(raw, batch_size=2)
        self.assertEqual(parsed[1], {"labels": ["study"], "source": "unknown", "is_missing": False})
        self.assertEqual(parsed[2], {"labels": [], "source": "unknown", "is_missing": True})


class TestFallbackParsing(unittest.TestCase):
    def test_whole_word_source(self):
        """Scanning for app names must only match whole words; 'next' must not yield source 'x'."""
        labels, source, is_fallback = parse_output("The next image shows some content.")
        self.assertTrue(is_fallback)
        self.assertEqual(source, "unknown")
        self.assertNotIn("x", source)

        labels_x, source_x, is_fallback_x = parse_output("This was found on x yesterday.")
        self.assertTrue(is_fallback_x)
        self.assertEqual(source_x, "x")

    def test_whole_word_categories(self):
        """Category matching must match whole words only.
        
        Note on 'study notes': both 'study' and 'notes' are distinct keys in
        common.CATEGORIES. Because both words appear as whole words, the fallback
        parser matches both keys, yielding ['notes', 'study'].
        """
        labels, source, is_fallback = parse_output("study notes")
        self.assertTrue(is_fallback)
        self.assertEqual(labels, ["notes", "study"])

        # Substrings inside longer words must NOT match
        labels_sub, source_sub, is_fallback_sub = parse_output("studying notebooks")
        self.assertTrue(is_fallback_sub)
        self.assertEqual(labels_sub, [])


if __name__ == "__main__":
    unittest.main()
