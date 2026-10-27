# Interpretation: writing style per app

After transcription, WYSAWYG shapes the raw transcript for where it is going. This runs
before the transcript is saved for recovery, so recovered text is shaped too. Any failure
inserts the raw transcript instead.

## Pipeline

1. **Prepare** (`DictationFormatter.prepare`, instant, always local)
   - Voice commands: "new line", "new paragraph" (only when spoken as a command, so "a new
     line of credit" is untouched; single-line fields get a space), "scratch that" (deletes
     back to the previous sentence).
   - Filler words: um, uh, erm, er, ahm. Case-sensitive so "ER" survives.
   - Self-corrections: "at 5, no wait, 6" → "at 6". Strong cues (no wait, sorry I mean, or
     rather, make that…) replace up to four words; "actually"/"I mean" only after a comma
     and for one or two words, so "Actually I liked it" stays as said.
2. **Smart cleanup** (optional, off by default): Qwen3 0.6B through LiteRT-LM fixes
   sentence breaks, question marks and list commas. Its answer is used only when
   `DictationFormatter.keepsWords` confirms it changed nothing but punctuation and capitals
   (dropping fillers or a repeated word is allowed). Skipped for short fields, very casual
   style and fewer than four words; abandoned after 8 seconds.
3. **Finish** (`DictationFormatter.finish`)
   - App style. Categories follow Wispr Flow: Personal messages, Work messages, Email,
     Everything else. Each can be Formal, Casual or Very casual (Settings → Writing style).
     - Formal: capitals, full punctuation, adds a final full stop.
     - Casual (default for messages): drops commas after interjections ("Yeah, sure"),
       around discourse words ("like", "mate"), before conjunctions, and commas that cut
       off one or two words; keeps lists and numbers; no final full stop.
     - Very casual: lowercase sentence starts (keeps "I" and acronyms), no commas or full
       stops; keeps ? and !.
   - Short fields (search, URL, email address, names, email subject): no trailing full
     stop, no line breaks.
   - Cursor fit: continuing a sentence lowercases a common first word ("…today, maybe
     tomorrow"), names stay capitalised; text after the cursor that starts in lowercase
     drops our full stop.

Categories come from the package name (`AppCategory`), then the editor's input type
(email subject → Email, short message → Personal). Try dictation can pretend to be
WhatsApp, Slack or Gmail. Settings → Preview styles shows every style for typed text.

## Smart cleanup model

- `litert-community/Qwen3-0.6B`, file `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`, pinned to
  commit `fed81d17`, 344,671,744 bytes, SHA-256
  `03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856`. Apache-2.0, ungated.
- Downloaded only when the user turns Smart cleanup on (resumable, verified before use) into
  `noBackupFilesDir/models`. Dictation itself never uses the network.
- The engine loads on first use and unloads after two idle minutes.
- Why rules first: in desktop tests the same model, asked to "clean up" freely, answered
  "say hello", translated "Translate hello into Spanish" and deleted clauses. Limited to
  punctuation with tagged input and examples, it kept every word in all 14 test cases and
  split run-on sentences correctly (≈1.6 s per message on an M-series Mac CPU).

Re-tune the prompt with `scripts/smart-cleanup-eval/eval.py` against the same model file.
