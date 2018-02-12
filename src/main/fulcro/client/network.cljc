(ns fulcro.client.network
  (:refer-clojure :exclude [send])
  (:require [fulcro.logging :as log]
            [cognitect.transit :as ct]
    #?(:cljs [goog.events :as events])
            [fulcro.transit :as t]
            [clojure.string :as str])
  #?(:cljs (:import [goog.net XhrIo EventType ErrorCode])))

#?(:cljs
   (defn make-xhrio "This is here (not inlined) to make mocking easier." [] (XhrIo.)))

#?(:cljs
   (defn xhrio-dispose "dispose of resources held by the xhrio object. Useful to wrap this for mocking"
     [xhrio] (.dispose xhrio)))
#?(:cljs
   (defn xhrio-enable-progress-events [xhrio] (.setProgressEventsEnabled xhrio true)))
#?(:cljs
   (defn xhrio-abort [xhrio] (.abort xhrio)))
#?(:cljs
   (defn xhrio-send [xhrio url verb body headers] (.send xhrio url verb body headers)))


; Newer protocol that should be used for new networking remotes.
(defprotocol FulcroHTTPRemoteI
  (transmit [this request complete-fn update-fn error-fn]
    "Send the given `request`, which will contain:
     - `:fulcro.client.network/edn` : The actual API tx to send.

     It may also optionally include:
     - `:fulcro.client.network/abort-id` : An ID to remember the network request by, to enable user-level API abort

     Exactly one call of complete-fn or error-fn will happen to indicate how the request finished (aborted requests call
     error-fn).

     Two or more calls to update-fn will occur. Once when the request is sent, once when the overall request is complete (any
     status), and zero or more times during data transfer. It will receive a single map containing the keys
     :progress and :status. The former will be one of `:sending`, `:receiving`, `:failed`, or `:complete`. The latter will
     be the low-level XhrIo progress event.

     complete-fn will be called with a (middleware) response and a query.
     error-fn is `(fn [reason detail])`. Reason will be one of:
       :middleware-aborted - The middleware threw an exception. `detail` will be an exception thrown by the middleware.
       :middleware-failed - The middleware failed to provide a well-formed request. `detail` will be the errant output of the middleware.
       :network-failed - The request did not complete at the network layer. `detail` will include
                         :error-code and :error-text. :error-code will be one of :exception, http-error, :timeout, or :abort.")
  (cancel [this kw-or-id]
    "Cancel the network activity for the given request, by request-id or load marker keyword. May be a no-op if
     the request wasn't tracked or is already done.")
  (network-behavior [this]
    "Returns flags indicating how this remote should behave in the Fulcro stack. Returned flags can include:

     `:fulcro.client.network/serial?` - Should Fulcro create a FIFO queue for requests to this remote, or should all
                                        requests be allowed to go immediately? If not supplied it defaults to true."))

(defn wrap-fulcro-request
  "Client Remote Middleware to add transit encoding for normal Fulcro requests. Sets the content type and transforms an EDN
  body to a transit+json encoded body. addl-transit-handlers is a map from data type to transit handler (like
  you would pass using the `:handlers` option of transit). The
  additional handlers are used to encode new data types into transit. See transit documentation for more details."
  ([middleware addl-transit-handlers]
    #?(:clj identity
       :cljs
            (let [writer (t/writer (if addl-transit-handlers {:handlers addl-transit-handlers} {}))]
              (fn [{:keys [headers body] :as request}]
                (let [body    (ct/write writer body)
                      headers (assoc headers "Content-Type" "application/transit+json")]
                  (middleware (merge request {:body body :headers headers})))))))
  ([middleware] (wrap-fulcro-request middleware nil))
  ([] (wrap-fulcro-request identity nil)))

(defn wrap-fulcro-response
  "Client remote middleware to transform a network response to a standard Fulcro form.

  addl-transit-handlers is equivalent to the :handlers option in transit: a map from data type to handler.
  "
  ([] (wrap-fulcro-response identity))
  ([middleware] (wrap-fulcro-response identity nil))
  ([middleware addl-transit-handlers]
    #?(:clj identity
       :cljs
            (let [base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                  handlers      (if (map? addl-transit-handlers) (merge base-handlers addl-transit-handlers) base-handlers)
                  reader        (t/reader {:handlers handlers})]
              (fn [{:keys [body status-code status-text error-code error-text] :as request}]
                (try (let [new-body (if (str/blank? body)
                                      status-code
                                      (ct/read reader body))]
                       (assoc request :body new-body))
                     (catch js/Object e
                       (log/error "Transit decode failed!" e)
                       request)))))))

#?(:cljs
   (def xhrio-error-states {(.-NO_ERROR ErrorCode)   :none
                            (.-EXCEPTION ErrorCode)  :exception
                            (.-HTTP_ERROR ErrorCode) :http-error
                            (.-ABORT ErrorCode)      :abort
                            (.-TIMEOUT ErrorCode)    :timeout}))
(defn extract-response
  "Generate a response map from the status of the given xhrio object, which could be in a complete or error state."
  [tx request xhrio]
  #?(:clj  {}
     :cljs {:original-tx      tx
            :outgoing-request request
            :body             (.getResponseText xhrio)
            :status-code      (.getStatus xhrio)
            :status-text      (.getStatusText xhrio)
            :error            (get xhrio-error-states (.getLastErrorCode xhrio) :unknown)
            :error-text       (.getLastError xhrio)}))

(defn clear-request* [active-requests id xhrio]
  (if (every? #(= xhrio %) (get active-requests id))
    (dissoc active-requests id)
    (update active-requests id disj xhrio)))

(defn response-extractor*
  [response-middleware edn real-request xhrio]
  #?(:cljs
     (fn []
       (let [r (extract-response edn real-request xhrio)]
         (try
           (response-middleware r)
           (catch :default e
             (log/debug "Client response middleware threw an exception. " e ". Defaulting to raw response.")
             (merge r {:middleware-failure e})))))))

(defn cleanup-routine*
  [abort-id active-requests xhrio]
  #?(:cljs (fn []
             (when abort-id
               (swap! active-requests clear-request* abort-id xhrio))
             (xhrio-dispose xhrio))))

(defn ok-routine*
  [progress-fn get-response-fn complete-fn error-fn]
  #?(:cljs
     (fn []
       (let [r (get-response-fn)]
         (if-let [failure (get r :middleware-failure)]
           (do
             (log/error "Client middleware threw an exception" failure)
             (progress-fn :failed {})
             (error-fn :middleware-aborted failure))
           (do
             (progress-fn :complete {})
             (complete-fn r)))))))

(defn progress-routine*
  [update-fn]
  (fn [direction event]
    (when update-fn
      (update-fn {:progress direction
                  :status   event}))))

(defn error-routine*
  [get-response progress-fn error-fn]
  (fn []
    (progress-fn :failed {})
    (error-fn :network-error (get-response))))

(defrecord FulcroHTTPRemote [url request-middleware response-middleware active-requests]
  FulcroHTTPRemoteI
  (transmit [this {:keys [::edn ::abort-id] :as raw-request} complete-fn error-fn update-fn]
    #?(:cljs
       (let [base-request   {:headers {} :body edn :url url :method :post}
             real-request   (request-middleware base-request)
             xhrio          (make-xhrio)
             {:keys [body headers url method incremental?]} real-request
             http-verb      (-> (or method :post) name str/upper-case)
             get-response   (response-extractor* response-middleware edn real-request xhrio)
             cleanup        (cleanup-routine* abort-id active-requests xhrio)
             progress-fn    (progress-routine* update-fn)
             response-ok    (ok-routine* progress-fn get-response complete-fn error-fn)
             response-error (error-routine* get-response progress-fn error-fn)]
         (when abort-id
           (swap! active-requests update abort-id (fnil conj #{}) xhrio))
         (log/debug (str "Sending " real-request " with headers " headers " to " url " via " http-verb))
         (when update-fn
           (xhrio-enable-progress-events xhrio)
           (events/listen xhrio (.-DOWNLOAD_PROGRESS EventType) #(progress-fn :receiving %))
           (events/listen xhrio (.-UPLOAD_PROGRESS EventType) #(progress-fn :sending %)))
         (events/listen xhrio (.-COMPLETE EventType) cleanup)
         (events/listen xhrio (.-SUCCESS EventType) response-ok)
         (events/listen xhrio (.-ERROR EventType) response-error)
         (xhrio-send xhrio url http-verb body headers))))
  (cancel [this id]
    (when-let [xhrio (get @active-requests id)]
      (xhrio-abort xhrio)))
  (network-behavior [this] {::serial? true}))

(defn fulcro-http-remote
  "Create a remote that (by default) communicates with the given url.

  The request middleware is a `(fn [request] modified-request)`. The `request` will have `:url`, `:body`, `:method`, and `:headers`. The
  request middleware defaults to `wrap-fulcro-request` (which encodes the request in transit+json). The result of this
  middleware chain on the outgoing request becomes the real outgoing request. It is allowed to modify the `url`.
  If the the request middleware returns a corrupt request or throws an exception then the remote code
  will immediately abort the request. The return value of the middleware will be used to generate a request to `:url`,
  with `:method` (e.g. :post), and the given headers. The body will be sent as-is without further translation.

  `response-middleware` is a function that returns a function `(fn [response] mod-response)` and
  defaults to `wrap-fulcro-response` which decodes the raw response and transforms it back to a response that Fulcro can merge.
  The response will be a map containing the `:original-tx`, which is the
  original Fulcro EDN request; `:outgoing-request` which is the exact request sent on the network; `:body`, which
  is the raw data of the response. Additionally, there will be one or more of the following to indicate low-level
  details of the result: `:status-code`, `:status-text`, `:error-code` (one of :none, :exception, :http-error, :abort, or :timeout),
  and `:error-text`.  Middleware is allowed to morph any of this to suit its needs.

  A result with a 200 status code will result in a merge using the resulting response's `:original-tx` as the query,
  and the `:body` as the EDN to merge. If the status code is anything else then the details of the response will be
  used when triggering the built-in error handling (e.g. fallbacks, global error handler, etc.)."
  [{:keys [url request-middleware response-middleware] :or {url                 "/api"
                                                            response-middleware (wrap-fulcro-response)
                                                            request-middleware  (wrap-fulcro-request)} :as options}]
  (map->FulcroHTTPRemote (merge options {:active-requests (atom {})})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Everything below this is DEPRECATED. Use code above this in new programs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare make-fulcro-network)

(defprotocol NetworkBehavior
  (serialize-requests? [this] "DEPRECATED. Returns true if the network is configured to desire one request at a time."))

(defprotocol ProgressiveTransfer
  (updating-send [this edn done-callback error-callback update-callback] "DEPRECATED. Send EDN. The update-callback will merge the state
  given to it. The done-callback will merge the state given to it, and indicates completion. See
  `fulcro.client.ui.file-upload/FileUploadNetwork` for an example."))

(defprotocol FulcroNetwork
  (send [this edn done-callback error-callback]
    "DEPRECATED. Send EDN. Calls either the done or error callback when the send is done. You must call one of those only once.
     Implement ProgressiveTransfer if you want to do progress updates during network transmission.")
  (start [this]
    "Starts the network."))

(defprotocol IXhrIOCallbacks
  (response-ok [this xhrio ok-cb] "Called by XhrIo on OK")
  (response-error [this xhrio err-cb] "Called by XhrIo on ERROR"))

#?(:cljs
   (defn parse-response
     "DEPRECATED. An XhrIo-specific implementation method for interpreting the server response."
     ([xhr-io] (parse-response xhr-io nil))
     ([xhr-io read-handlers]
      (try (let [text          (.getResponseText xhr-io)
                 base-handlers {"f" (fn [v] (js/parseFloat v)) "u" cljs.core/uuid}
                 handlers      (if (map? read-handlers) (merge base-handlers read-handlers) base-handlers)]
             (if (str/blank? text)
               (.getStatus xhr-io)
               (ct/read (t/reader {:handlers handlers})
                 (.getResponseText xhr-io))))
           (catch js/Object e {:error 404 :message "Server down"})))))

(defrecord Network [url request-transform global-error-callback complete-app transit-handlers]
  NetworkBehavior
  (serialize-requests? [this] true)
  IXhrIOCallbacks
  (response-ok [this xhr-io valid-data-callback]
    ;; Implies:  everything went well and we have a good response
    ;; (i.e., got a 200).
    #?(:cljs
       (try
         (let [read-handlers  (:read transit-handlers)
               query-response (parse-response xhr-io read-handlers)]
           (when valid-data-callback (valid-data-callback (or query-response {}))))
         (finally (.dispose xhr-io)))))
  (response-error [this xhr-io error-callback]
    ;; Implies:  request was sent.
    ;; *Always* called if completed (even in the face of network errors).
    ;; Used to detect errors.
    #?(:cljs
       (try
         (let [status                 (.getStatus xhr-io)
               log-and-dispatch-error (fn [str error]
                                        ;; note that impl.application/initialize will partially apply the
                                        ;; app-state as the first arg to global-error-callback
                                        (log/error str)
                                        (error-callback error)
                                        (when @global-error-callback
                                          (@global-error-callback status error)))]
           (if (zero? status)
             (log-and-dispatch-error
               (str "NETWORK ERROR: No connection established.")
               {:type :network})
             (log-and-dispatch-error
               (str "SERVER ERROR CODE: " status)
               (parse-response xhr-io transit-handlers))))
         (finally (.dispose xhr-io)))))
  FulcroNetwork
  (send [this edn ok error]
    #?(:cljs
       (let [xhrio     (make-xhrio)
             handlers  (or (:write transit-handlers) {})
             headers   {"Content-Type" "application/transit+json"}
             {:keys [body headers]} (cond-> {:body edn :headers headers}
                                      request-transform request-transform)
             post-data (ct/write (t/writer {:handlers handlers}) body)
             headers   (clj->js headers)]
         (events/listen xhrio (.-SUCCESS EventType) #(response-ok this xhrio ok))
         (events/listen xhrio (.-ERROR EventType) #(response-error this xhrio error))
         (.send xhrio url "POST" post-data headers))))
  (start [this] this))

(defn make-fulcro-network
  "DERECATED: Use `make-fulcro-remote` instead.

  Build a Fulcro Network object using the default implementation.

  Features:

  - `:url` is the target URL suffix (URI) on the server for network requests. defaults to /api.
  - `:request-transform` is a (fn [{:keys [body headers] :as req}] req') to transform arbitrary requests (e.g. to add things like auth headers)
  - `:global-error-callback` is a global error callback (fn [app-state-map status-code error] ) that is notified when a 400+ status code or hard network error occurs
  - `transit-handlers` is a map of transit handlers to install on the reader, such as

   `{ :read { \"thing\" (fn [wire-value] (convert wire-value))) }
      :write { Thing (ThingHandler.) } }`

   where:

   (defrecord Thing [foo])

   (deftype ThingHandler []
     Object
     (tag [_ _] \"thing\")
     (rep [_ thing] (make-raw thing))
     (stringRep [_ _] nil)))
  "
  [url & {:keys [request-transform global-error-callback transit-handlers]}]
  (map->Network {:url                   url
                 :transit-handlers      transit-handlers
                 :request-transform     request-transform
                 :global-error-callback (atom global-error-callback)}))

(defrecord MockNetwork
  [complete-app]
  FulcroNetwork
  (send [this edn ok err] (log/info "Ignored (mock) Network request " edn))
  (start [this] this))

(defn mock-network [] (map->MockNetwork {}))
