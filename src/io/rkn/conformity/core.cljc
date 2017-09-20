(ns io.rkn.conformity.core
  (:require [dat.sync.db :as ds]
            [datascript.core :as datascript]
            #?(:clj [datomic.api :as d])
            #?(:clj [clojure.java.io :as io])))

(def ^:deprecated default-conformity-attribute :confirmity/conformed-norms)

(def ensure-norm-tx-txfn
  "Transaction function to ensure each norm tx is executed exactly once"
  (ds/function
    {:lang :clojure
     :requires [[dat.sync.db :as ds]]
     :params [db norm-attr norm index-attr index tx]
     :code (when-not (seq (ds/q '[:find ?tx
                                 :in $ ?na ?nv ?ia ?iv
                                 :where [?tx ?na ?nv ?tx] [?tx ?ia ?iv ?tx]]
                               db norm-attr norm index-attr index))
             (cons {:db/id (ds/tempid! db :db.part/tx)
                    norm-attr norm
                    index-attr index}
                   tx))}))

#?(:clj
(defn read-resource
  "Reads and returns data from a resource containing edn text. An
  optional argument allows specifying opts for clojure.edn/read"
  ([resource-name]
   (read-resource {:readers *data-readers*} resource-name))
  ([opts resource-name]
   (->> (io/resource resource-name)
        (io/reader)
        (java.io.PushbackReader.)
        (clojure.edn/read opts)))))

(defn index-attr
  "Returns the index-attr corresponding to a conformity-attr"
  [conformity-attr]
  (keyword (namespace conformity-attr)
           (str (name conformity-attr) "-index")))

(defn has-attribute?
  "Returns true if a database has an attribute named attr-name"
  [db attr-name]
  (-> (ds/entity db attr-name)
      :db.install/_attribute
      boolean))

(defn has-function?
  "Returns true if a database has a function named fn-name"
  [db fn-name]
  (-> (ds/entity db fn-name)
      :db/fn
      boolean))

(defn default-conformity-attribute-for-db
  "Returns the default-conformity-attribute for a db."
  [db]
  (or (some #(and (has-attribute? db %) %) [:conformity/conformed-norms default-conformity-attribute])
      :conformity/conformed-norms))

(defn ensure-conformity-schema
  "Ensure that the two attributes and one transaction function
  required to track conformity via the conformity-attr keyword
  parameter are installed in the database."
  [conn conformity-attr]
  (when-not (has-attribute? (ds/db conn) conformity-attr)
    (ds/transact! conn [{:db/id (ds/tempid! conn :db.part/db)
                        :db/ident conformity-attr
                        :db/valueType :db.type/keyword
                        :db/cardinality :db.cardinality/one
                        :db/doc "Name of this transaction's norm"
                        :db/index true
                        :db.install/_attribute :db.part/db}]))
  (when-not (has-attribute? (ds/db conn) (index-attr conformity-attr))
    (ds/transact! conn [{:db/id (ds/tempid! conn :db.part/db)
                        :db/ident (index-attr conformity-attr)
                        :db/valueType :db.type/long
                        :db/cardinality :db.cardinality/one
                        :db/doc "Index of this transaction within its norm"
                        :db/index true
                        :db.install/_attribute :db.part/db}]))
  (when-not (has-function? (ds/db conn) ::ensure-norm-tx-txfn)
    (ds/transact! conn [{:db/id (ds/tempid! conn :db.part/user)
                        :db/ident ::ensure-norm-tx-txfn
                        :db/doc "Ensures each norm tx is executed exactly once"
                        :db/fn ensure-norm-tx-txfn
}])))

(defn conforms-to?
  "Does database have a norm installed?

      conformity-attr  (optional) the keyword name of the attribute used to
                       track conformity
      norm             the keyword name of the norm you want to check
      tx-count         the count of transactions for that norm"
  ([db norm tx-count]
   (conforms-to? db (default-conformity-attribute-for-db db) norm tx-count))
  ([db conformity-attr norm tx-count]
   (and (has-attribute? db conformity-attr)
        (pos? tx-count)
        (-> (ds/q '[:find ?tx
                   :in $ ?na ?nv
                   :where [?tx ?na ?nv ?tx]]
                 db conformity-attr norm)
            count
            (= tx-count)))))

#?(:clj
(defn maybe-timeout-synch-schema [conn maybe-timeout]
  (if maybe-timeout
    (let [result (deref (d/sync-schema conn (d/basis-t (d/db conn))) maybe-timeout ::timed-out)]
      (if (= result ::timed-out)
        (throw (ex-info "Timed out calling synch-schema between conformity transactions" {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn))))))

(defn reduce-txes
  "Reduces the seq of transactions for a norm into a transaction
  result accumulator"
  [acc conn norm-attr norm-name txes sync-schema-timeout]
  (reduce
   (fn [acc [tx-index tx]]
     (try
       (let [safe-tx (ds/call conn [::ensure-norm-tx-txfn norm-attr norm-name (index-attr norm-attr) tx-index tx])
             _ (case (ds/db-kind conn)
                 :datascript nil
                 :wrapped-datomic #?(:clj (maybe-timeout-synch-schema (:conn conn) sync-schema-timeout))
                 :datomic #?(:clj (maybe-timeout-synch-schema conn sync-schema-timeout)))
             tx-result (ds/transact! conn [safe-tx])]
         (if (next (:tx-data tx-result))
           (conj acc {:norm-name norm-name
                      :tx-index tx-index
                      :tx-result tx-result})
           acc))
       (catch #?(:clj Throwable
                 :cljs :default) t
         (let [reason (.getMessage t)
               data {:succeeded acc
                     :failed {:norm-name norm-name
                              :tx-index tx-index
                              :reason reason}}]
           (throw (ex-info reason data t))))))
   acc (map-indexed vector txes)))

;; TODO get conformity functions working
;; (defn eval-txes-fn
;;   "Given a connection and a symbol referencing a function on the classpath...
;;      - `require` the symbol's namespace
;;      - `resolve` the symbol
;;      - evaluate the function, passing it the connection
;;      - return the result"
;;   [conn txes-fn]
;;   (try (require (symbol (namespace txes-fn)))
;;        {:txes ((resolve txes-fn) conn)}
;;        (catch Throwable t
;;          {:ex (str "Exception evaluating " txes-fn ": " t)})))

(defn get-norm
  "Pull from `norm-map` the `norm-name` value. If the norm contains a
  `txes-fn` key, allow processing of that key to stand in for a `txes`
  value. Returns the value containing transactable data."
  [conn norm-map norm-name]
  (let [{:keys [txes txes-fn] :as norm} (get norm-map norm-name)]
;;     (cond->
      norm
;;       txes-fn (merge (eval-txes-fn conn txes-fn)))
  ))

(defn reduce-norms
  "Reduces norms from a norm-map specified by a seq of norm-names into
  a transaction result accumulator"
  [acc conn norm-attr norm-map norm-names]
  (let [sync-schema-timeout (:conformity.setting/sync-schema-timeout norm-map)]
    (reduce
     (fn [acc norm-name]
       (let [requires (-> norm-map norm-name :requires)
             acc (cond-> acc
                   requires (reduce-norms conn norm-attr norm-map requires))
             {:keys [txes ex]} (get-norm conn norm-map norm-name)]
         (cond
           (conforms-to? (ds/db conn) norm-attr norm-name (count txes))
           acc

           (empty? txes)
           (let [reason (or ex
                            (str "No transactions provided for norm "
                                 norm-name))
                 data {:succeeded acc
                       :failed {:norm-name norm-name
                                :reason reason}}]
             (throw (ex-info reason data)))

           :else
           (reduce-txes acc conn norm-attr norm-name txes sync-schema-timeout))))
     acc norm-names)))

(defn ensure-conforms
  "Ensure that norms represented as datoms are conformed-to (installed), be they
  schema, data or otherwise.

      conformity-attr  (optional) the keyword name of the attribute used to
                       track conformity
      norm-map         a map from norm names to data maps.
                       a data map contains:
                         :txes     - the data to install
                         :txes-fn  - An alternative to txes, pointing to a
                                     symbol representing a fn on the classpath that
                                     will return transactions.
                         :requires - (optional) a list of prerequisite norms
                                     in norm-map.
      norm-names       (optional) A collection of names of norms to conform to.
                       Will use keys of norm-map if not provided.

  On success, returns a vector of maps with values for :norm-name, :tx-index,
  and :tx-result for each transaction that improved the db's conformity.

  On failure, throws an ex-info with a reason and data about any partial
  success before the failure."
  ([conn norm-map]
   (ensure-conforms conn norm-map (keys norm-map)))
  ([conn norm-map norm-names]
   (ensure-conforms conn (default-conformity-attribute-for-db (ds/db conn)) norm-map norm-names))
  ([conn conformity-attr norm-map norm-names]
   (ensure-conformity-schema conn conformity-attr)
   (reduce-norms [] conn conformity-attr norm-map norm-names)))

#?(:clj
(defn- speculative-conn*
  "Creates a mock datomic.Connection that speculatively applies transactions using datomic.api/with"
  [db]
  (let [state (atom {:db-after db})
        wrap-listenable-future (fn [value]
                                 (reify datomic.ListenableFuture
                                   (get [this] value)
                                   (get [this timeout time-unit] value)
                                   (toString [this] (prn-str value))))]
    (reify datomic.Connection
      (db [_] (:db-after @state))
      (transact [_ tx-data]
        (let [tx-result-after (swap! state #(ds/with (:db-after %) tx-data))]
          (wrap-listenable-future tx-result-after)))
      (sync [_] (wrap-listenable-future (:db-after @state)))
      (sync [_ t] (wrap-listenable-future (:db-after @state)))
      (syncSchema [_ t] (wrap-listenable-future (:db-after @state)))))))

(defn- speculative-conn [db]
  (case (ds/db-kind db)
    :datascript (datascript/conn-from-db db)
    :datomic #?(:cljs nil :clj (speculative-conn* db))))

(defn with-conforms
  "Variation of ensure-conforms that speculatively ensures norm are conformed to

   On success, returns a map with:
     :db     the resulting database that conforms the the provided norms
     :result a vector of maps with values for :norm-name, :tx-index,
             and :tx-result for each transaction that improved the db's conformity.

   On failure, throws an ex-info with a reason and data about any partial
   success before the failure."
  ([db norm-map]
   (with-conforms db norm-map (keys norm-map)))
  ([db norm-map norm-names]
   (with-conforms db (default-conformity-attribute-for-db db) norm-map norm-names))
  ([db conformity-attr norm-map norm-names]
   (let [conn (speculative-conn db)]
     (ensure-conformity-schema conn conformity-attr)
     (let [result (reduce-norms [] conn conformity-attr norm-map norm-names)]
       {:db (ds/db conn)
        :result result}))))
