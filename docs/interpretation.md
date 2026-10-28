# Interpretation: writing style per app

After transcription, WYSAWYG shapes the raw transcript for where it is going. This runs
before the transcript is saved for recovery, so recovered text is shaped too. Any failure
inserts the raw transcript instead.

## Pipeline

1. **Prepare** (`DictationFormatter.prepare`, instant, always local)
   - Voice commands: "new line", "new paragraph" (only when spoken as a command, so "a new
     line of credit" is untouched; single-line fields get a space), "scratch that" (deletes
     the last phrase, back to the previous comma or full stop; "don't scratch that car" is
     ordinary speech).
   - Filler words: um, uh, erm, er, ahm. Case-sensitive so "ER" survives.
   - Self-corrections: "at 5, no wait, 6" → "at 6". The correction replaces the earlier words
     from the last one it lines up with: the same word ("the red one, no wait, the blue
     one"), a number, a day or a name ("ask Tom, no wait, Sarah, to call"). With no anchor
     it stays as said. Cues: no wait, no sorry, sorry I mean, I mean to say, or rather; plus
     "actually"/"I mean" after a comma for up to three words. The bundled engine often
     writes a correction as its own sentences ("at five. No wait. Six."); those are rejoined.
2. **Smart cleanup** (optional, off by default): Qwen3 0.6B through LiteRT-LM fixes
   sentence breaks, question marks and list commas. Its answer is used only when
   `DictationFormatter.keepsWords` confirms it changed nothing but punctuation and capitals
   (dropping filler words is the only other change allowed). Skipped for short fields, very casual
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

## Checks without a phone

`InterpretationIntegrationTest` runs a real recording through the bundled engine and the
formatter (and Smart cleanup, if installed), logging under `WysawygInterpret`:

```sh
say -v Daniel -o casual.wav --file-format=WAVE --data-format=LEI16@16000 "Hey, um, yeah, I'm running a bit late. I'll be there at five, no wait, six."
adb push casual.wav /data/local/tmp/ && adb shell "cat /data/local/tmp/casual.wav | run-as com.cragnet.wysawyg sh -c 'cat > files/casual.wav'"
adb shell am instrument -w -e class com.cragnet.wysawyg.InterpretationIntegrationTest \
  -e interpretFixture /data/data/com.cragnet.wysawyg/files/casual.wav com.cragnet.wysawyg.test/androidx.test.runner.AndroidJUnitRunner
```

Results on an Android 14 arm64 emulator (Apple Silicon, 27 Oct 2026):

| Spoken | Engine | WhatsApp |
|---|---|---|
| Hey, um, yeah, I'm running a bit late. I'll be there at five, no wait, six… | Hey, um yeah, I'm running a bit late. I'll be there at five. No wait. Six. Did you… | Hey yeah I'm running a bit late. I'll be there at six. Did you want me to grab anything on the way? |
| Can you ask Tom, no wait, Sarah, to send me the invoice by Friday, sorry, I mean Thursday? | Can you ask Tom? No wait, Sarah, to send me the invoice by Friday. Sorry, I mean Thursday. | Can you ask Sarah to send me the invoice by Thursday (Smart cleanup adds the ?) |

Smart cleanup took about 2 s warm and 4–6 s including model load on the emulator CPU. It
sometimes splits sentences wrongly while keeping every word ("Did you get home? Ok and did
you tell Sam?"), which is why it stays optional.
