# Sweep AI NES Local

A JetBrains IDE plugin for **Next Edit Suggestion (NES)** code completion — inline, Tab-to-accept
suggestions that predict your next edit, not just the next token. This build is **local-only**:
there is no cloud account, no login, and no network dependency beyond whatever completion backend
you point it at yourself.

## What it does

As you type, the plugin tracks your recent edits and cursor position and asks a completion backend
to predict your next edit. The suggestion renders as inline ghost text; press **Tab** to accept or
**Escape** to reject.

Two completion backends are supported, configured in IDE Settings (see below):

- **Bundled local server** — the plugin manages a local `uvx sweep-autocomplete` process for you.
  Zero manual setup; good default.
- **OpenAI-compatible endpoint** — point the plugin at any server exposing `POST /v1/completions`
  (llama.cpp's `llama-server`, LM Studio, vLLM, text-generation-webui, etc.), running a
  `sweep-next-edit` model yourself. Gives you full control over hardware, quantization, and speed.

Everything unrelated to code completion (chat, agent mode, MCP, BYOK providers, commit-message
generation, PR review) has been removed from this build.

## Building from source

Requires JDK 21.

```bash
./gradlew buildPlugin
```

The installable plugin zip is written to `build/distributions/`. To install it:

1. In the IDE: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the zip from `build/distributions/`.
3. Restart the IDE.

There's also a convenience script that builds and installs directly into your local IDE's plugin
directory in one step, but it's **macOS-only** (hardcodes `~/Library/Application Support/JetBrains`)
and its glob only matches `IntelliJIdea*`, `PyCharm*`, `WebStorm*`, `CLion*`, `RubyMine*`, `Rider*` —
**not PhpStorm or GoLand, and not Windows/Linux**. On anything else, use Install Plugin from Disk
manually:

```bash
./bin/install
```

Other scripts in `bin/`: `format` (code formatting), `uninstall`, `release`. Note that `bin/install`
still has a leftover `--cloud` flag from before this build went local-only (sets an
`ENVIRONMENT=cloud` marker file and renames the output zip) — it doesn't do anything meaningful
anymore since the cloud backend no longer exists in this build; ignore it.

## Configuring

All settings live in **Settings/Preferences → Tools → Sweep**.

### Code Completion

| Setting | What it does |
| --- | --- |
| Enable code completion | Master on/off switch for NES suggestions |
| Accept word on right arrow | Lets the right-arrow key accept one word of the suggestion instead of the whole thing |
| Show badge in suggestion popup | Shows a small footer badge in the ghost-text popup |
| Automatically disable conflicting autocomplete plugins | Detects other autocomplete plugins (e.g. Full Line Completion) that fight over the same keystrokes and disables them |
| Debounce (ms) | How long to wait after you stop typing before requesting a suggestion |
| Exclude files | Newline-separated list of filename patterns to never suggest completions for |

### Local AI

| Setting | What it does |
| --- | --- |
| Enable local autocomplete server | Turns on the bundled `uvx sweep-autocomplete` server as the completion backend |
| Local server port | Port the bundled server listens on (default `8081`) |
| Start Server | Manually starts the bundled server in a terminal tab and shows its health status |
| Endpoint URL / Model / API key / Max tokens | Custom OpenAI-compatible endpoint. **If set, this overrides the bundled local server entirely.** |

Leave the endpoint URL blank to use the bundled local server; fill it in to bring your own
inference server instead.

### Models and recommended config for the OpenAI-compatible endpoint

Sweep's open-weight next-edit models are published at **[huggingface.co/sweepai](https://huggingface.co/sweepai)**,
including [`sweep-next-edit-1.5B`](https://huggingface.co/sweepai/sweep-next-edit-1.5B).

The **Model** field is load-bearing: the plugin changes its prompt format based on whether the
name contains `"7B"` (case-insensitive). Use a name *without* `7B` for the 1.5B model (e.g.
`sweep-next-edit-1.5b`) and a name *with* `7B` for the 7B variant.

**Recommended `llama-server` (llama.cpp) config for `sweep-next-edit-1.5B`:**

```bash
llama-server \
  -m /path/to/sweep-next-edit-1.5b.q8_0.v2.gguf \
  --port 1234 \
  -ngl 99 \
  -c 8192 \
  --parallel 1 \
  --flash-attn on \
  --cache-type-k q8_0 \
  --cache-type-v q8_0 \
  --lookup-cache-dynamic /tmp/sweep-lookup.bin \
  --spec-type ngram-mod \
  --spec-ngram-mod-n-max 16 \
  --spec-ngram-mod-n-min 2
```

| Flag | Why |
| --- | --- |
| `-ngl 99` | Offload all layers to GPU |
| `-c 8192` | Real prompts run ~1600–1900 tokens; 8K leaves headroom while keeping the KV cache small |
| `--parallel 1` | Required — the plugin's request-cancellation logic assumes a single inference slot |
| `--flash-attn on` | Faster attention (try bare `--flash-attn` if your build rejects the `on` argument) |
| `--cache-type-k/v q8_0` | Quantized KV cache — halves memory with little quality cost at this size |
| `--lookup-cache-dynamic <path>` | Persistent n-gram cache for prompt-lookup speculative decoding |
| `--spec-type ngram-mod` | Enables prompt-lookup speculative decoding — the biggest latency win for this workload, since next-edit output is mostly a verbatim copy of the input with a small splice |

Then set **Endpoint URL** to `http://localhost:1234`, **Model** to `sweep-next-edit-1.5b`, and
**Max tokens** to around `128` (real average output is ~70 tokens — keeping this low caps tail
latency).

Other servers work too: **LM Studio** is easiest to set up but lacks prompt-lookup speculative
decoding (slower). **vLLM** supports prompt-lookup via `speculative_config: {method: "ngram"}` if
you're on Linux+CUDA. Either way, make sure "Apply chat template" is **off** — this is a base
completion model, not a chat model.

## Customizing keybindings

The accept/reject keystrokes are fully customizable via IntelliJ's keymap settings.

### Defaults
- **Accept suggestion**: `Tab`
- **Reject suggestion**: `Escape`
- **Show last rejected suggestion**: `Alt+Shift+Backspace`

### How to customize
1. Open **Settings/Preferences → Keymap**
2. Search for **"Accept Autocomplete Suggestion"** or **"Reject Autocomplete Suggestion"**
3. Right-click the action and select **"Add Keyboard Shortcut"**
4. Assign your preferred keystroke (e.g. `Enter`, `Ctrl+Space`, etc.)
5. The plugin adapts to your custom keystrokes immediately — no restart needed

**Note**: the keystroke must map to a standard editor action (`TAB`, `ENTER`, `ESCAPE`, arrow keys,
etc.) to be intercepted reliably. Custom combinations that don't correspond to an editor action may
not work.

## IdeaVim integration

For IdeaVim users, put this in your `~/.ideavimrc`:

```vim
sethandler <Tab> a:ide
```

Or if that doesn't work:

```vim
map <Tab> :action dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction<CR>
```

You can remap to different keys:

```vim
" Use Enter to accept completions
map <CR> :action dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction<CR>

" Use Ctrl+Y to reject completions
map <C-y> :action dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction<CR>
```
