(ns electron.updater
  (:require [electron.utils :refer [mac? fetch]]
            [electron.logger :as logger]
            [frontend.version :refer [version]]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            ["semver" :as semver]
            ["child_process" :as child-process]
            ["crypto" :as crypto]
            ["os" :as os]
            ["fs" :as fs]
            ["path" :as node-path]
            ["electron" :refer [ipcMain app]]))

(def *update-ready-to-install (atom nil))
(def *update-pending (atom nil))
(def debug (partial logger/debug "[updater]"))

;Event: 'error'
;Event: 'checking-for-update'
;Event: 'update-available'
;Event: 'update-not-available'
;Event: 'download-progress'
;Event: 'update-downloaded'
;Event: 'completed'

(def electron-version
  (let [parts (string/split version #"\.")
        parts (take 3 parts)]
    (string/join "." parts)))

(defn- mac-asset-labels
  []
  (case (.-arch js/process)
    "arm64" ["darwin-arm64" "Apple-Silicon"]
    "x64" ["darwin-x64" "Intel"]
    []))

(defn- matching-mac-zip?
  [asset]
  (let [asset-name (:name asset)]
    (and (string? asset-name)
         (string/ends-with? asset-name ".zip")
         (some #(string/includes? asset-name %) (mac-asset-labels)))))

(defn get-latest-artifact-info
  [repo]
  (let [endpoint (str "https://api.github.com/repos/" repo "/releases/latest")]
    (debug "checking" endpoint)
    (p/catch
     (p/let [res (fetch endpoint {:headers {"Accept" "application/vnd.github+json"
                                             "User-Agent" "Logseq-Alfred-Updater"}
                                  :timeout (* 1000 10)})
             status (.-status res)
             text (when-not (.-ok res) (.text res))
             release-json (when (.-ok res) (.json res))]
       (if (.-ok res)
         (let [release (js->clj release-json :keywordize-keys true)
               remote-version (when-let [tag-name (:tag_name release)]
                                (re-find #"\d+\.\d+\.\d+" (str tag-name)))
               asset (first (filter matching-mac-zip? (:assets release)))]
           (when-not (and remote-version asset)
             (throw (js/Error. (str "The latest Alfred release has no compatible macOS ZIP for "
                                    (.-arch js/process)))))
           {:name (or (:name release) (:tag_name release))
            :notes (:body release)
            :version remote-version
            :url (:browser_download_url asset)
            :digest (:digest asset)
            :size (:size asset)})
         (throw (js/Error. (str "[" status "] " text)))))
     (fn [e]
       (logger/warn "[update server error]" e)
       (throw e)))))

(defn check-for-updates
  [{:keys           [repo ^js win]
    [auto-download] :args}]
  (let [emit (fn [type payload]
               (.. win -webContents
                   (send "updates-callback" (bean/->js {:type type :payload payload}))))]
    (debug "check for updates #" repo version)
    (p/create
     (fn [resolve reject]
       (emit "checking-for-update" nil)
       (-> (p/let
            [artifact (get-latest-artifact-info repo)

             artifact (when-let [remote-version (:version artifact)]
                        (when (and (. semver valid remote-version)
                                   (. semver lt electron-version remote-version)) artifact))

             url (if-not artifact (do (emit "update-not-available" nil) (throw nil)) (:url artifact))
             _ (if url (emit "update-available" (bean/->js artifact)) (throw (js/Error. "download url not exists")))
               ;; start download FIXME: user's preference about auto download
             _ (when-not auto-download (throw nil))
             ^js dl-res (fetch url)
             _ (when-not (.-ok dl-res) (throw (js/Error. "download resource not available")))
             dest-info (p/create
                        (fn [resolve1 reject1]
                          (let [headers (. dl-res -headers)
                                total-size (or (some-> (.get headers "content-length") js/parseInt)
                                               (:size artifact)
                                               0)
                                body (.-body dl-res)
                                start-at (.now js/Date)
                                *downloaded (atom 0)
                                digest (.createHash crypto "sha256")
                                expected-digest (:digest artifact)
                                dest-basename (node-path/basename url)
                                tmp-dest-file (node-path/join (os/tmpdir) (str dest-basename ".pending"))
                                dest-file (.createWriteStream fs tmp-dest-file)]
                            (doto body
                              (.on "data" (fn [chunk]
                                            (let [downloaded (+ @*downloaded (.-length chunk))
                                                  percent (if (pos? total-size)
                                                            (.toFixed (/ (* 100 downloaded) total-size) 2)
                                                            "0.00")
                                                  elapsed (/ (- (js/Date.now) start-at) 1000)]
                                              (.write dest-file chunk)
                                              (.update digest chunk)
                                              (emit "download-progress" {:total      total-size
                                                                         :downloaded downloaded
                                                                         :percent    percent
                                                                         :elapsed    elapsed})
                                              (reset! *downloaded downloaded))))
                              (.on "error" (fn [e]
                                             (reject1 e)))
                              (.on "end" (fn [_e]
                                           (.end dest-file
                                                 (fn []
                                                   (let [actual-digest (str "sha256:" (.digest digest "hex"))
                                                         dest-file (string/replace tmp-dest-file ".pending" "")]
                                                     (if (and expected-digest
                                                              (not= expected-digest actual-digest))
                                                       (do
                                                         (fs/unlinkSync tmp-dest-file)
                                                         (reject1 (js/Error. "Downloaded update checksum mismatch")))
                                                       (do
                                                         (when (fs/existsSync dest-file)
                                                           (fs/unlinkSync dest-file))
                                                         (fs/renameSync tmp-dest-file dest-file)
                                                         (resolve1 (merge artifact {:dest-file dest-file})))))))))))))]
             (reset! *update-ready-to-install dest-info)
             (emit "update-downloaded" dest-info)
             (.. win -webContents
                 (send "auto-updater-downloaded" (bean/->js dest-info)))
             (resolve nil))
           (p/catch
            (fn [e]
              (if e
                (do
                  (emit "error" e)
                  (reject e))
                (resolve nil))))
           (p/finally
             (fn []
               (emit "completed" nil))))))))

(defn- current-app-bundle
  []
  (-> js/process.execPath
      node-path/dirname
      node-path/dirname
      node-path/dirname))

(def ^:private staging-dir-prefix "logseq-alfred-update-")

(defn- staging-dir?
  [dir]
  (and (string? dir)
       (string/starts-with? (node-path/basename dir) staging-dir-prefix)))

(defn- remove-staging-dir!
  [dir]
  (when (staging-dir? dir)
    (try
      (fs/rmSync dir #js {:recursive true :force true})
      (catch :default e
        (logger/warn "[updater] the staging copy could not be removed" e)))))

(defn- cleanup-stale-staging-dirs!
  "Removes update copies left behind by interrupted installs. macOS keeps every
   app bundle it finds registered in LaunchServices, so a half deleted copy in
   the temporary folder can hijack the app launch later on."
  []
  (when mac?
    (try
      (let [tmp (os/tmpdir)
            one-hour-ago (- (js/Date.now) (* 60 60 1000))]
        (doseq [entry (js->clj (fs/readdirSync tmp))
                :let [dir (node-path/join tmp entry)]
                :when (staging-dir? dir)]
          (try
            ;; keep whatever an install running right now may still need
            (when (< (.-mtimeMs (fs/statSync dir)) one-hour-ago)
              (debug "removing stale staging copy" dir)
              (remove-staging-dir! dir))
            (catch :default _e nil))))
      (catch :default e
        (logger/warn "[updater] stale staging copies could not be swept" e)))))

(defn- extract-update!
  [zip-file]
  (when-not mac?
    (throw (js/Error. "Personal updates are currently supported only on macOS")))
  (let [staging-dir (fs/mkdtempSync (node-path/join (os/tmpdir) staging-dir-prefix))]
    (try
      (let [result (child-process/spawnSync "/usr/bin/ditto" #js ["-x" "-k" zip-file staging-dir])
            app-name (first (filter #(string/ends-with? % ".app")
                                    (js->clj (fs/readdirSync staging-dir))))]
        (when-not (zero? (.-status result))
          (throw (js/Error. "The downloaded update could not be extracted")))
        (when-not app-name
          (throw (js/Error. "The downloaded update contains no macOS application")))
        {:staging-dir staging-dir
         :app (node-path/join staging-dir app-name)})
      (catch :default e
        (remove-staging-dir! staging-dir)
        (throw e)))))

(def update-helper-script
  "pid=\"$1\"
source_app=\"$2\"
target_app=\"$3\"
staging_dir=\"$4\"
zip_file=\"$5\"
backup_app=\"${target_app}.previous\"
cleanup() {
  case \"$staging_dir\" in
    */logseq-alfred-update-*) /bin/rm -rf \"$staging_dir\" ;;
  esac
}
trap cleanup EXIT
while /bin/kill -0 \"$pid\" 2>/dev/null; do /bin/sleep 0.2; done
/bin/rm -rf \"$backup_app\"
if /bin/mv \"$target_app\" \"$backup_app\" && /usr/bin/ditto \"$source_app\" \"$target_app\"; then
  /usr/bin/xattr -dr com.apple.quarantine \"$target_app\" 2>/dev/null || true
  case \"$zip_file\" in
    *.zip) /bin/rm -f \"$zip_file\" ;;
  esac
  /usr/bin/open \"$target_app\"
else
  /bin/rm -rf \"$target_app\"
  if [ -d \"$backup_app\" ]; then /bin/mv \"$backup_app\" \"$target_app\"; fi
  /usr/bin/open \"$target_app\"
fi")

(defn- install-personal-update!
  [zip-file]
  (let [target-app (current-app-bundle)
        target-parent (node-path/dirname target-app)]
    (when-not (string/ends-with? target-app ".app")
      (throw (js/Error. "Logseq Alfred is not running from an application bundle")))
    (when (string/starts-with? target-app "/Volumes/")
      (throw (js/Error. "Move Logseq Alfred to Applications before updating")))
    (fs/accessSync target-parent (.-W_OK (.-constants fs)))
    (let [{:keys [staging-dir app]} (extract-update! zip-file)
          helper (child-process/spawn "/bin/sh"
                                      #js ["-c" update-helper-script
                                           "logseq-alfred-updater"
                                           (str js/process.pid)
                                           app
                                           target-app
                                           staging-dir
                                           zip-file]
                                      #js {:detached true :stdio "ignore"})]
      (.unref helper))))

(defn init-updater
  [{:keys [repo ^js _win] :as opts}]
  (let [check-channel "check-for-updates"
        install-channel "install-updates"
        check-listener (fn [_e & args]
                         (when-not @*update-pending
                           (reset! *update-pending true)
                           (p/finally
                             (check-for-updates (merge opts {:args args}))
                             #(reset! *update-pending nil))))
        install-listener (fn [_e quit-app?]
                           (if-let [dest-file (:dest-file @*update-ready-to-install)]
                             (do
                               (install-personal-update! dest-file)
                               (when quit-app?
                                 (js/setTimeout #(.quit app) 500))
                               true)
                             false))]
    (cleanup-stale-staging-dirs!)
    (.handle ipcMain check-channel check-listener)
    (.handle ipcMain install-channel install-listener)
    #(do
       (.removeHandler ipcMain install-channel)
       (.removeHandler ipcMain check-channel)
       (reset! *update-pending nil))))
