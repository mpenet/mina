(ns s-exp.mina.request
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (io.helidon.common.http Http$HeaderValue)
           (io.helidon.nima.webserver.http ServerRequest ServerResponse)))

;; Try to decode headers against a static table first, and fallback to
;; `str/lower-case` if there are no matches
(def header-key->ring-header-key
  (eval `(fn [k#]
           (case k#
             ~@(mapcat (juxt identity str/lower-case)
                       (->> "headers.txt"
                            io/resource
                            io/reader
                            line-seq))
             (str/lower-case k#)))))

(defn ring-headers
  [^ServerRequest server-request]
  (-> (reduce (fn [m ^Http$HeaderValue h]
                (assoc! m
                        (header-key->ring-header-key (.name h))
                        (.values h)))
              (transient {})
              (.headers server-request))
      persistent!))

(defn ring-method
  [^ServerRequest server-request]
  (let [method (-> server-request
                   .prologue
                   .method
                   .text)]
    ;; mess with the string as a last resort, try to match against static values
    ;; first
    (case method
      "GET" :get
      "POST" :post
      "PUT" :put
      "DELETE" :delete
      "HEAD" :head
      "OPTIONS" :options
      "TRACE" :trace
      "PATCH" :patch
      (keyword (str/lower-case method)))))

(defn ring-protocol
  [^ServerRequest server-request]
  (case (-> server-request
            .prologue
            .protocolVersion)
    "1.0" "HTTP/1.0"
    "1.1" "HTTP/1.1"
    "2.0" "HTTP/2"))

(defn ring-request
  [^ServerRequest server-request
   ^ServerResponse server-response]
  (let [address ^java.net.InetSocketAddress (.address (.remotePeer server-request))
        local-peer (.localPeer server-request)
        content (.content server-request)]
    {:body (when-not (.consumed content) (.inputStream content))
     :server-port (.port local-peer)
     :server-name (.host local-peer)
     :remote-addr (-> address .getAddress .getHostAddress)
     :uri (.rawPath (.path server-request))
     :query-string (let [query (.rawValue (.query server-request))]
                     (when (not= "" query) query))
     :scheme (if (.isSecure server-request) :https :http)
     :protocol (ring-protocol server-request)
     :ssl-client-cert (some-> server-request .remotePeer .tlsCertificates (.orElse nil) first)
     :request-method (ring-method server-request)
     :headers (ring-headers server-request)
     ::server-request server-request
     ::server-response server-response}))

