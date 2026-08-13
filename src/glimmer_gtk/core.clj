(ns glimmer-gtk.core
  "The GTK4 backend for glimmer. Requiring this namespace installs it, after
  which glimmer's portable reconciler (glimmer.core) renders hiccup into real
  GTK widgets:

    (ns myapp
      (:require [glimmer.ratom :refer [atom]]
                [glimmer.core :as ui]
                [glimmer-gtk.core]))          ; installs the GTK4 backend

    (defn -main [& _] (ui/run my-app :title \"hello\"))

  What this namespace supplies to glimmer.backend is the toolkit half of the
  seam: element creation and prop application (glimmer-gtk.widget), container
  child management, the app loop, and the marshalling of off-thread work onto
  that loop.

  The app loop is a GtkApplication whose :activate handler creates a window and
  hands it to the reconciler to mount into; g_application_run then blocks running
  the GTK main loop, and every signal/activate callback is a :collect-safe
  foreign-callable."
  (:require [glimmer.backend :as b]
            [glimmer-gtk.ffi :as g]
            [glimmer-gtk.widget :as w]
            [jolt.ffi :as ffi]))

;; --- marshalling work onto the GTK main loop ---------------------------------
;; While a GTK app runs, g_application_run owns the main thread. A ratom mutation
;; made off that thread (an nREPL eval on its worker thread, or any future) would
;; otherwise reconcile — calling GTK — off the main thread, which AppKit rejects
;; on macOS. glimmer.core defers those re-renders through backend/schedule, which
;; lands here.
(defn- post-to-gui
  "Schedule zero-arg `work` on the GTK main loop via a one-shot g_idle_add
  source. The source returns FALSE (0) so it fires once and is removed; the
  retained callable is released after running so a long REPL session doesn't
  accumulate them."
  [work]
  (let [slot (atom nil)]
    (reset! slot (ffi/foreign-callable (fn [_data]
                                         (let [cb @slot]
                                           (try (work)
                                                (finally (w/release-callable! cb))))
                                         0)
                                       [:pointer] :int :collect-safe))
    (w/retain-callable! @slot)
    (g/g-idle-add @slot ffi/null)
    nil))

;; --- the app loop ------------------------------------------------------------
(defn- run*
  "Create the GtkApplication, and on :activate build a window, hand it to
  `mount-root!` (which mounts the root component into it), present it, and run
  the GTK main loop. Blocks until the app quits. backend/loop-running? is set for
  the duration so reactive changes marshal onto the loop instead of rendering
  inline on whatever thread wrote the cell."
  [opts mount-root!]
  (let [{:keys [app-id title width height auto-quit-ms]
         :or {app-id "glimmer.app" title "glimmer" width 400 height 300}} opts
        app (g/gtk-application-new app-id g/APPLICATION-DEFAULT-FLAGS)
        activate (fn [_app _data]
                   (let [win (g/gtk-application-window-new app)]
                     (g/gtk-window-set-title win title)
                     (g/gtk-window-set-default-size win width height)
                     (mount-root! win :window)
                     (g/gtk-window-present win)
                     (when auto-quit-ms
                       (let [quit (ffi/foreign-callable
                                    (fn [_data] (g/g-application-quit app) 0)
                                    [:pointer] :int :collect-safe)]
                         (w/retain-callable! quit)
                         (g/g-timeout-add auto-quit-ms quit ffi/null)))))
        activate-cb (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe)]
    (w/retain-callable! activate-cb)
    (g/g-signal-connect-data app "activate" activate-cb ffi/null ffi/null g/CONNECT-DEFAULT)
    (try
      (reset! b/loop-running? true)
      (g/g-application-run app 0 ffi/null)
      (finally (reset! b/loop-running? false)))))

(defn- run!
  "Backend entry point for glimmer.core/run.

  On macOS, g_application_run must run on the process main thread or AppKit
  aborts when it sets the main menu. Under `jolt nrepl-server` the primordial
  thread parks in jolt.host/park-until-interrupt (a main-thread pump); this hops
  the boot onto it ASYNCHRONOUSLY via jolt.host/call-on-main-thread-async and
  returns right away, so the nREPL eval that started the app completes and the
  session stays live for reactive edits (swap! a ratom to re-render, or redefine
  components and call glimmer.core/reload! to re-render the running window in
  place — both marshal onto the main loop). The GUI itself then runs on that main
  thread. Under `jolt run` (or any non-jolt host) there is no pump, so it runs
  inline and blocks until the app quits."
  [opts mount-root!]
  (let [start (fn [] (run* opts mount-root!))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))

;; --- the backend -------------------------------------------------------------
(def backend
  "The GTK4 backend map handed to glimmer.backend/register!. See that namespace
  for the contract each key satisfies."
  {:name           :gtk4
   :create!        w/create!
   :apply-props!   w/apply-props!
   :append-child!  w/append-child!
   :remove-child!  w/remove-child!
   :replace-child! w/replace-child!
   :reorder-child! w/reorder-child!
   :schedule       post-to-gui
   :run            run!})

(defn install!
  "Make GTK4 the backend glimmer renders with. Called on load, so requiring this
  namespace is enough; exposed for code that wants to be explicit, or to switch
  back after another backend was installed."
  []
  (b/register! backend)
  nil)

(defonce ^:private _installed (do (install!) true))
