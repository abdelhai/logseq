(ns electron.core
  (:require [electron.handler :as handler]
            [electron.updater :refer [init-updater]]
            [electron.utils :refer [mac? win32? prod? dev? logger open]]
            [clojure.string :as string]
            [promesa.core :as p]
            ["fs-extra" :as fs]
            ["path" :as path]
            ["electron" :refer [BrowserWindow app protocol ipcMain dialog] :as electron]
            ["electron-window-state" :as windowStateKeeper]
            [clojure.core.async :as async]
            [electron.state :as state]))

(def ROOT_PATH (path/join js/__dirname ".."))
(def MAIN_WINDOW_ENTRY (str "file://" (path/join js/__dirname (if dev? "electron-dev.html" "electron.html"))))

(defonce *setup-fn (volatile! nil))
(defonce *teardown-fn (volatile! nil))

;; Handle creating/removing shortcuts on Windows when installing/uninstalling.
(when (js/require "electron-squirrel-startup") (.quit app))

(defn create-main-window
  "Creates main app window"
  []
  (let [win-state (windowStateKeeper (clj->js {:defaultWidth 980 :defaultHeight 700}))
        win-opts {:width         (.-width win-state)
                  :height        (.-height win-state)
                  :frame         (not mac?)
                  :autoHideMenuBar (not mac?)
                  :titleBarStyle (if mac? "hidden" nil)
                  :webPreferences
                  {:plugins                 true ; pdf
                   :nodeIntegration         false
                   :nodeIntegrationInWorker false
                   :contextIsolation        true
                   :spellcheck              true
                   :preload                 (path/join js/__dirname "js/preload.js")}}
        url MAIN_WINDOW_ENTRY
        win (BrowserWindow. (clj->js win-opts))]
    (.manage win-state win)
    (.loadURL win url)
    (when dev? (.. win -webContents (openDevTools)))
    win))

(defn setup-updater! [^js win]
  ;; manual/auto updater
  (init-updater {:repo   "logseq/logseq"
                 :logger logger
                 :win    win}))

(defn setup-interceptor! []
  (.registerFileProtocol
   protocol "assets"
   (fn [^js request callback]
     (let [url (.-url request)
           path (string/replace url "assets://" "")
           path (js/decodeURIComponent path)]
       (callback #js {:path path}))))
  #(.unregisterProtocol protocol "assets"))

(defn- handle-export-publish-assets [_event html custom-css-path]
  (let [app-path (. app getAppPath)
        paths (js->clj (. dialog showOpenDialogSync (clj->js {:properties ["openDirectory" "createDirectory" "promptToCreate", "multiSelections"]})))]
    (when (seq paths)
      (let [root-dir (first paths)
            static-dir (path/join root-dir "static")
            index-html-path (path/join root-dir "index.html")]
        (p/let [_ (. fs ensureDir static-dir)
                _ (p/all  (concat
                           [(. fs writeFile index-html-path html)

                            (. fs copy (path/join app-path "404.html") (path/join root-dir "404.html"))]

                           (map
                            (fn [part]
                              (. fs copy (path/join app-path part) (path/join static-dir part)))
                            ["css" "fonts" "icons" "img" "js"])))
                custom-css (. fs readFile custom-css-path)
                _ (. fs appendFile (path/join static-dir "css" "style.css") custom-css)
                js-files ["main.js" "code-editor.js" "excalidraw.js"]
                _ (p/all (map (fn [file]
                                (. fs removeSync (path/join static-dir "js" file)))
                              js-files))
                _ (p/all (map (fn [file]
                                (. fs moveSync
                                   (path/join static-dir "js" "publishing" file)
                                   (path/join static-dir "js" file)))
                              js-files))
                _ (. fs removeSync (path/join static-dir "js" "publishing"))
                ;; remove source map files
                ;; TODO: ugly, replace with ls-files and filter with ".map"
                _ (p/all (map (fn [file]
                                (. fs removeSync (path/join static-dir "js" (str file ".map"))))
                              ["main.js" "code-editor.js" "excalidraw.js" "age-encryption.js"]))]
          (. dialog showMessageBox (clj->js {:message (str "Export publish assets to " root-dir " successfully")})))))))

(defn setup-app-manager!
  [^js win]
  (let [toggle-win-channel "toggle-max-or-min-active-win"
        call-app-channel "call-application"
        export-publish-assets "export-publish-assets"
        web-contents (. win -webContents)]
    (doto ipcMain
      (.handle toggle-win-channel
               (fn [_ toggle-min?]
                 (when-let [active-win (.getFocusedWindow BrowserWindow)]
                   (if toggle-min?
                     (if (.isMinimized active-win)
                       (.restore active-win)
                       (.minimize active-win))
                     (if (.isMaximized active-win)
                       (.unmaximize active-win)
                       (.maximize active-win))))))

      (.handle export-publish-assets handle-export-publish-assets)

      (.handle call-app-channel
               (fn [_ type & args]
                 (try
                   (js-invoke app type args)
                   (catch js/Error e
                     (js/console.error e))))))

    (.on web-contents  "new-window"
         (fn [e url]
           (let [url (if (string/starts-with? url "file:")
                       (js/decodeURIComponent url) url)
                 url (if-not win32? (string/replace url "file://" "") url)]
             (.. logger (info "new-window" url))
             (open url))
           (.preventDefault e)))

    (doto win
      (.on "enter-full-screen" #(.send web-contents "full-screen" "enter"))
      (.on "leave-full-screen" #(.send web-contents "full-screen" "leave")))

    #(do (.removeHandler ipcMain toggle-win-channel)
         (.removeHandler ipcMain export-publish-assets)
         (.removeHandler ipcMain call-app-channel))))

(defonce *win (atom nil))

(defn- destroy-window!
  [^js win]
  (.destroy win))

(defn main
  []
  (if-not (.requestSingleInstanceLock app)
    (.quit app)
    (do
      (.on app "second-instance"
           (fn [_event _commandLine _workingDirectory]
             (when-let [win @*win]
               (when (.isMinimized win)
                 (.restore win))
               (.focus win))))
      (.on app "window-all-closed" (fn [] (.quit app)))
      (.on app "ready"
           (fn []
             (let [^js win (create-main-window)
                   _ (reset! *win win)
                   *quitting? (atom false)]
               (.. logger (info (str "Logseq App(" (.getVersion app) ") Starting... ")))

               (vreset! *setup-fn
                        (fn []
                          (let [t0 (setup-updater! win)
                                t1 (setup-interceptor!)
                                t2 (setup-app-manager! win)
                                tt (handler/set-ipc-handler! win)]

                            (vreset! *teardown-fn
                                     #(doseq [f [t0 t1 t2 tt]]
                                        (and f (f)))))))

               ;; setup effects
               (@*setup-fn)

               ;; main window events
               (.on win "close" (fn [e]
                                  (.preventDefault e)
                                  (let [web-contents (. win -webContents)]
                                    (.send web-contents "persistent-dbs"))
                                  (async/go
                                    ;; FIXME: What if persistence failed?
                                    (let [_ (async/<! state/persistent-dbs-chan)]
                                      (if (or @*quitting? (not mac?))
                                        (when-let [win @*win]
                                          (destroy-window! win)
                                          (reset! *win nil))
                                        (do (.preventDefault ^js/Event e)
                                            (.hide win)))))))
               (.on app "before-quit" (fn [_e] (reset! *quitting? true)))
               (.on app "activate" #(if @*win (.show win)))))))))

(defn start []
  (js/console.log "Main - start")
  (when @*setup-fn (@*setup-fn)))

(defn stop []
  (js/console.log "Main - stop")
  (when @*teardown-fn (@*teardown-fn)))
