(ns circle.model.build
  "Main definition of the Build object. "
  (:require [clojure.string :as str])
  (:require fs)
  (:use [circle.util.except :only (throw-if-not)]
        [circle.util.args :only (require-args)])
  (:require [clj-time.core :as time])
  (:require [circle.util.model-validation :as v])
  (:require [circle.backend.ssh :as ssh])
  (:require [circle.model.project :as project])
  (:use [circle.util.model-validation-helpers :only (is-ref? require-keys)])
  (:use [circle.util.predicates :only (ref?)])
  (:use [circle.util.mongo :only (object-id)])
  (:require [somnium.congomongo :as mongo])
  (:require [circle.util.mongo :as c-mongo])
  (:require [circle.sh :as sh])
  (:use [clojure.tools.logging :only (log)])
  (:require [circle.backend.github-url :as github-url])
  (:require [robert.hooke :as hooke]))

(def build-coll :builds) ;; mongo collection for builds

(def build-defaults {:continue? true
                     :action-results []})

(def node-validation
  [(require-keys [:username])])

(def build-validations
  [(require-keys [:_id
                  :_project_id
                  :build_num
                  :vcs_url
                  :vcs_revision])
   ;; (fn [build]
   ;;   (v/validate node-validation (-> build :node)))
   (fn [b]
     (when (and (= :deploy (:type b)) (not (-> b :vcs_revision)))
       "version-control revision is required for deploys"))
   (fn [b]
     (when-not (and (-> b :build_num (integer?))
                    (-> b :build_num (pos?)))
       "build_num must be a positive integer"))])

(defn validate [b]
  (v/validate build-validations b))

(defn valid? [b]
  (v/valid? build-validations b))

(defn validate!
  "Validates the contents of a build. i.e. pass the map, not the ref"
  [b]
  {:pre [(not (ref? b))]}
  (v/validate! build-validations b))

(def build-dissoc-keys
  ;; Keys on build that shouldn't go into mongo, for whatever reason
  [:actions :action-results])

(defn insert! [row]
  (mongo/insert! :builds (apply dissoc row build-dissoc-keys)))

(defn update-mongo
  "Given a build ref, update the mongo row with the current values of b."
  [b]
  (throw-if-not (-> @b :_id) "build must have id")
  (mongo/update! build-coll
                 {:_id (-> @b :_id)}
                 (apply dissoc @b build-dissoc-keys)))

(defn build
  "Creates and returns the build ref, updates/inserts the DB if necessary"
  [{:keys [_id
           vcs_url
           vcs_revision ;; if present, the commit that caused the build to be run, or nil
           actions      ;; a seq of actions
           node         ;; Map containing keys required by ec2/start-instance
           lb-name      ;; name of the load-balancer to use
           continue?    ;; if true, continue running the build. Failed actions will set this to false
           ]
    :as args}]
  (let [project (project/get-by-url! vcs_url)
        build_num (project/next-build-num project)
        build-id (or _id (object-id))
        build (ref
               (merge build-defaults
                      args
                      {:_id build-id
                       :build_num build_num
                       :_project_id (-> project :_id)})
               :validator validate!)]
    (update-mongo build)
    build))

(defn project-name [b]
  {:post [(seq %)]}
  (-> @b :vcs_url (github-url/parse) :project))

(defn extend-group-with-revision
  "update the build, setting the pallet group-name to extends the
  existing group with the VCS revision."
  [build]
  (dosync
   (alter build
          assoc-in [:group :group-name] (keyword (.toLowerCase (format "%s-%s" (project-name build) (-> @build :vcs_revision))))))
  build)

(defn build-name
  ([build]
     (build-name (project-name build) (-> @build :build_num)))
  ([project-name build-num]
     (str project-name "-" build-num)))

(defn checkout-dir
  "Directory where the build will be checked out, on the build box."
  ([build]
     (checkout-dir (project-name build) (-> @build :build_num)))
  ([project-name build-num]
     (str/replace (build-name project-name build-num) #" " "-")))

(defn successful? [build]
  (and (-> @build :stop_time)
       (-> @build :continue?)))

(defn log-ns
  "returns the name of the logger to use for this build "
  [build]
  (symbol (str "circle.build." (project-name build) "-" (-> @build :build_num))))

(defn get-project [build]
  {:post [%]}
  (project/get-by-url (-> @build :vcs_url)))

(def ^:dynamic *log-ns* nil) ;; contains the name of the logger for the current build

(defn ssh-build-log
  "This is a different function from the normal build log because 1)
  ssh/handle-out won't pass format arguments. 2) if the string does happen to
  contain a %s, we don't want format throwing because we have a %s and
  no extra args"
  [str level]
  (when *log-ns*
    (log *log-ns* level nil str)))

(defn log-ssh-out
  [level]
  (fn [f str]
    (ssh-build-log str level)
    (f str)))

;; def, so hooke doesn't re-add hooks on every fn call
(def handle-out (log-ssh-out :info))
(def handle-err (log-ssh-out :error))

(hooke/add-hook #'ssh/handle-out handle-out)
(hooke/add-hook #'ssh/handle-error handle-err)

(defmacro with-build-log-ns [build & body]
  `(binding [*log-ns* (log-ns ~build)]
     ~@body))

(defn build-log [message & args]
  (when *log-ns*
    (log *log-ns* :info nil (apply format message args))))

(defn build-log-error [message & args]
  (when *log-ns*
    (log *log-ns* :error nil (apply format message args))))

(defn build-with-instance-id
  "Returns the build from the DB with the given instance-id"
  [id]
  (mongo/fetch-one build-coll :where {:instance-ids id}))

(defn ssh
  "Opens a terminal window that SSHs into instance with the provided id.

Assumes:
  1) the instance was started by a build
  2) the build is in the DB
  3) the instance is still running
  4) this clojure process is on OSX"

  [instance-id]
  (let [build (build-with-instance-id instance-id)
        ssh-private-key (-> build :node :private-key)
        username (-> build :node :username)
        ip-addr (-> build :node :ip-addr)
        key-temp-file (fs/tempfile "ssh")
        _ (spit key-temp-file ssh-private-key)
        _ (fs/chmod "-r" key-temp-file)
        _ (fs/chmod "u+r" key-temp-file)
        ssh-cmd (format "ssh -i %s %s@%s" key-temp-file username ip-addr)
        tell-cmd (format "'tell app \"Terminal\" \ndo script \"%s\"\n end tell'" ssh-cmd)]
    (sh/shq (osascript -e ~tell-cmd))))