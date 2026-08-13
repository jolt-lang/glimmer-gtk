# glimmer-gtk

The **GTK4** backend for [glimmer](https://github.com/jolt-lang/glimmer), the
reactive GUI toolkit for [jolt](https://github.com/jolt-lang/jolt).

glimmer owns the portable half — reactive cells, the component model, the
reconciler — and knows nothing about any toolkit. This project supplies the other
half for GTK4: the C bindings, the hiccup tag and prop vocabulary, the `:on-*`
signal wiring, and the `g_application_run` app loop. Requiring
`glimmer-gtk.core` registers it, and glimmer renders into real GTK widgets.

```clojure
(ns myapp
  (:require [glimmer.ratom :as r :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-gtk.core]))            ; installs the GTK4 backend

(defn counter []
  (let [count (atom 0)]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "- 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]
        [:button {:label "reset" :on-click #(reset! count 0)}]]])))

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
```

Components, reactive state and reconciliation are documented in glimmer's README.
What follows is the GTK-specific part: what you can put in the hiccup.

## Requirements

GTK4 and GLib must be installed.

- macOS: `brew install gtk4`
- Linux: `apt install libgtk-4-dev` (or your distro's equivalent)

The native libraries (`glib-2.0`, `gobject-2.0`, `gio-2.0`, `gtk-4`) are declared
in `deps.edn` under `:jolt/native` and loaded automatically when the namespaces
are required.

## Running

```sh
jolt test      # unit tests (Pango markup, prop normalization — no display needed)
jolt smoke     # reactivity smoke against the live GTK loop (needs a display)
jolt keyed     # keyed reconciliation smoke against the live widget tree
jolt counter   # interactive counter demo (opens a window, blocks)
jolt todo      # interactive todo demo (opens a window, blocks)
```

## Hiccup reference

Elements are `[:tag props? & children]`. `props` is an optional map; children may
be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped.

**Containers:** `:window` (single child), `:box` (`:orientation :horizontal|:vertical`),
`:hbox`, `:vbox`, `:frame` (single child, with an optional `:label`),
`:scrolled` (single child; the child scrolls instead of forcing the window bigger).

**Leaf widgets:** `:button`, `:label`, `:entry`, `:checkbutton`, `:separator`.

**Common props (apply to every widget):** margins and alignment, resolved from
idiomatic keywords at runtime (see [Enum constants](#enum-constants) below):

- `:margin` (all four sides), or `:margin-start`/`:margin-end`/`:margin-top`/`:margin-bottom`
- `:halign`/`:valign` — one of `:fill :start :end :center :baseline-fill :baseline-center`
- `:hexpand`/`:vexpand` — boolean
- `:width-request`/`:height-request` — int

**Per-tag props:**

- Window: `:title`, `:width`, `:height`, `:visible`
- Box: `:orientation`, `:spacing`, `:homogeneous`
- Button: `:label`, `:tooltip`, `:sensitive`
- Label: `:label`/`:text`, `:markup` (Pango markup), `:xalign` (0.0–1.0),
  `:wrap` (boolean), `:max-width-chars`/`:width-chars` (int — cap natural width so a
  long line can't drive its container wider), `:lines` (int, with `:wrap`),
  `:ellipsize` (`:none`/`:start`/`:middle`/`:end`)
- Entry: `:text`, `:placeholder`, `:sensitive`
- Checkbutton: `:label`, `:active`
- Frame: `:label`
- Scrolled: `:scroll-top` — any change to this value scrolls back to the top, for
  a panel whose content is replaced

**Events:**

- `:on-click` — button clicked. Handler takes no args.
- `:on-change` — entry text changed. Handler receives the current text.
- `:on-activate` — entry activated (Enter). Handler takes no args.
- `:on-toggled` — checkbutton toggled. Handler takes no args.

Signals are connected once at mount. Handlers should close over reactive cells
(not values), so the first render's closure stays correct for the widget's life.
A programmatic prop change (setting `:text` on an entry, `:active` on a
checkbutton) suppresses the signal GTK emits for it, so a re-render can't feed
back into its own handler.

## Pango markup

A label's `:markup` prop takes a Pango markup string, or hiccup data that is
validated and serialized for you:

```clojure
[:label {:markup [:span {:foreground "#8e939d"} "Nothing to do yet"]}]
[:label {:markup [:b [:i "bold italic"]]}]
```

Pango's markup is a small XML subset, not HTML, so the data is checked against
Pango's own vocabulary first: an HTML-only tag (`:div`, `:br`) or a typo'd
attribute throws at the call site instead of rendering silently wrong. Attribute
names use Pango's spelling with underscores (`:font_family`, `:letter_spacing`),
and text is escaped for you.

## Enum constants

GTK enum/flag values (`GTK_ALIGN_START`, `GTK_ORIENTATION_VERTICAL`, …) are
resolved **at runtime from keyword nicks**, not maintained as a constant table.
Every GObject enum registers its members with a lowercase nick that *is* a
Clojure keyword (`:start`, `:fill`, `:horizontal`); `glimmer-gtk.genum` looks the
nick up in the GObject type registry and returns its integer value. So you write
`:halign :start`, `:orientation :vertical` — no `GTK_ALIGN_*` constants anywhere
in the library. A raw integer also works as a fallback.

One wrinkle: a type is only in the registry once its owning widget class has
initialized, so enum props are applied in the re-render path (where the widget
already exists). Plain `#define` numeric macros (rare here) can't be resolved
this way and are declared explicitly where truly needed.

## Extending the widget set

A consumer can teach glimmer-gtk new hiccup tags at load time:

```clojure
(require '[glimmer-gtk.widget :as w])

(w/register-widget! :my-thing
  {:ctor      (fn [props] (make-the-gtk-widget props))
   :apply     (fn [widget props] (re-apply props on re-render))
   :container :none})          ; or :box / :window / :frame / :scrolled

(w/register-signal! :on-input "value-changed"
                    (fn [widget] (read-the-value widget)))  ; value-fn optional
```

See `glimmer-gl.gtk` for a worked example (`:gl-area`, `:scale`).

## Architecture

Four namespaces:

- **`glimmer-gtk.ffi`** — thin `defcfn` bindings to GTK4 / GLib. No logic.
- **`glimmer-gtk.genum`** — resolves GObject enum members from keyword nicks
  (`:start`, `:fill`) to their integer values at runtime via the GObject type
  registry, so the library needs no enum-constant tables.
- **`glimmer-gtk.widget`** — hiccup to GTK: tag to constructor, props to setters,
  `:on-*` to GTK signals wired through `foreign-callable`, and container child
  management. The tag and signal registries are open (`register-widget!`,
  `register-signal!`).
- **`glimmer-gtk.core`** — the backend map handed to `glimmer.backend/register!`,
  the `g_application_run` app loop, and the `g_idle_add` marshalling that lets
  off-main-thread code (an nREPL eval) trigger a repaint safely.

## Live development

Under `jolt nrepl-server`, `ui/run` hops the GTK loop onto the process main
thread (AppKit requires it on macOS) and returns immediately, so the REPL session
stays live. Mutate a `defonce` reactive cell and the window repaints; after
redefining components call `(glimmer.core/reload!)` to re-mount the root in the
same window.
